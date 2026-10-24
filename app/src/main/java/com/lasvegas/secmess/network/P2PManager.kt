package com.lasvegas.secmess.network

import android.content.Context
import android.util.Log
import com.lasvegas.secmess.crypto.CryptoManager
import com.lasvegas.secmess.crypto.KeyStoreHelper
import com.lasvegas.secmess.crypto.P2PCrypto
import com.lasvegas.secmess.crypto.PairKeyStore
import com.lasvegas.secmess.crypto.PairState
import com.lasvegas.secmess.model.Message
import com.lasvegas.secmess.repository.MessageRepository
import com.lasvegas.secmess.util.NotificationHelper
import io.matthewnelson.kmp.tor.runtime.core.ctrl.HiddenServiceEntry
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3.PrivateKey.Companion.toED25519_V3PrivateKey
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transporte P2P 1:1 (fase 111).
 *
 * Cada device expõe um onion service v3 próprio, com a chave guardada no Keystore.
 * O servidor **não transporta conteúdo**: só distribui o descritor (onion + chaves
 * públicas) aos aprovados. As mensagens vão de onion para onion, cifradas ponta-a-ponta
 * (X25519 efémero + HKDF + AES-256-GCM) e assinadas com Ed25519 pelo sender.
 *
 * Protocolo (JSON por linha, sobre TCP dentro do circuito Tor):
 *   -> {"t":"hello","from","to","nonce","ts","sig"}
 *   <- {"t":"hello_ok","nonce","sig"}
 *   -> {"t":"msg","env":{id,from,to,ts,eph,iv,ct,sig}}
 *   <- {"t":"ack","id"}
 */
object P2PManager {
    private const val TAG = "OGP2P"
    const val PORT = 8787

    private val handler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "corrotina P2P falhou: ${t.message}", t)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
    private val json = Json { ignoreUnknownKeys = true }

    private var appContext: Context? = null
    private var helper: KeyStoreHelper? = null
    private var listener: ServerSocket? = null
    private var listenerJob: Job? = null
    private var watchJob: Job? = null
    private val onionMutex = Mutex()

    @Volatile private var onionAddress: String? = null
    @Volatile private var myUid: String = ""

    private val _address = MutableStateFlow<String?>(null)
    val address = _address.asStateFlow()

    /** Chamado quando o descritor fica pronto (para o relay o publicar). */
    @Volatile var onDescriptorReady: ((String) -> Unit)? = null

    private val seenIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val lastAttempt = ConcurrentHashMap<String, Long>()
    // Uma ligação de cada vez por peer + cooldown crescente depois de falhas (evita a
    // tempestade de circuitos Tor quando um onion acabou de ser publicado).
    private val peerLocks = ConcurrentHashMap<String, Mutex>()
    private val peerCooldownUntil = ConcurrentHashMap<String, Long>()
    private val peerFailures = ConcurrentHashMap<String, Int>()

    fun start(context: Context) {
        synchronized(this) {
            if (watchJob?.isActive == true) return
            val app = context.applicationContext
            appContext = app
            val ks = KeyStoreHelper(app)
            helper = ks
            myUid = ks.getUserId()
            PairKeyStore.init(app)
            PeerDirectory.init(app)
            startListener()

            watchJob = scope.launch {
                TorManager.socksPort.collect { port ->
                    if (port == null) {
                        onionAddress = null
                        _address.value = null
                    } else if (onionAddress == null) {
                        try {
                            ensureOnion()
                        } catch (t: Throwable) {
                            Log.e(TAG, "ensureOnion falhou: ${t.message}", t)
                        }
                    }
                }
            }
            // Se o ADD_ONION falhar no arranque (Tor lento), não ficar sem onion para sempre.
            scope.launch {
                while (isActive) {
                    delay(60_000)
                    if (onionAddress == null && TorManager.socksPort.value != null) {
                        try { ensureOnion() } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    /**
     * O processo Tor reiniciou: o onion service antigo morreu. Recria-o com a mesma
     * chave (o endereço mantém-se) e volta a publicar o descritor. Sem isto, o
     * descritor fica no servidor mas ninguém consegue ligar-se de fora — o sintoma
     * é uma direção funcionar e a outra não.
     */
    fun onTorRestart() {
        onionAddress = null
        _address.value = null
        scope.launch {
            repeat(60) {
                if (TorManager.controlPort() != null) {
                    try { ensureOnion() } catch (t: Throwable) {
                        Log.w(TAG, "ensureOnion pós-restart falhou: ${t.message}")
                    }
                    return@launch
                }
                delay(1_000)
            }
        }
    }

    fun stop() {
        watchJob?.cancel(); watchJob = null
        listenerJob?.cancel(); listenerJob = null
        try { listener?.close() } catch (_: Exception) {}
        listener = null
        onionAddress = null
        _address.value = null
    }

    /** JSON publicado ao servidor: {onion, x25519, signing}. */
    fun descriptorJson(): String? {
        val onion = onionAddress ?: return null
        val x25519 = helper?.getPublicKey() ?: return null
        val signing = helper?.signingPublicB64() ?: return null
        val host = if (onion.endsWith(".onion")) onion else "$onion.onion"
        return buildJsonObject {
            put("onion", host)
            put("x25519", android.util.Base64.encodeToString(x25519, android.util.Base64.NO_WRAP))
            put("signing", signing)
        }.toString()
    }

    fun canReach(uid: String): Boolean =
        uid.isNotBlank() && uid != myUid && PeerDirectory.get(uid) != null

    /**
     * Cria o envelope E2E para o fallback pelo relay (o onion do par pode estar
     * em baixo). O servidor transporta só este JSON cifrado; a assinatura Ed25519
     * e o X25519 efémero continuam a ser verificados pelo recetor.
     */
    fun relayEnvelope(msg: Message): String? {
        val desc = PeerDirectory.get(msg.receiverId) ?: return null
        val ks = helper ?: return null
        val plaintext = try {
            String(CryptoManager.decrypt(msg.ciphertext, ks.getOrCreateLocalKey()), Charsets.UTF_8)
        } catch (_: Throwable) {
            return null
        }
        val psk = PairKeyStore.psk(msg.receiverId)
        val envelopeV3 = psk != null && PairKeyStore.isVerified(msg.receiverId)
        val (eph, iv, ct) = P2PCrypto.encryptTo(
            desc.x25519,
            plaintext.toByteArray(),
            if (envelopeV3) psk else null,
        )
        val canonical = envelopeCanonical(
            if (envelopeV3) 3 else 1,
            msg.type,
            msg.id, msg.senderId, msg.receiverId, msg.timestamp.toString(), eph, iv, ct,
        )
        val sig = ks.signP2P(canonical.toByteArray())
        return buildJsonObject {
            put("id", msg.id)
            put("from", msg.senderId)
            put("to", msg.receiverId)
            put("ts", msg.timestamp.toString())
            put("eph", eph)
            put("iv", iv)
            put("ct", ct)
            put("sig", sig)
            if (envelopeV3) put("v", 3)
            if (msg.type != "chat") put("type", msg.type)
        }.toString()
    }

    /** Recebe um envelope vindo pelo relay e devolve o id para o `delivery_ack`. */
    fun acceptRelayEnvelope(context: Context, raw: String): String? {
        return try {
            acceptEnvelope(Json.parseToJsonElement(raw).jsonObject)
        } catch (_: Throwable) {
            null
        }
    }

    /** Evita tentar o mesmo destinatário a cada 3 s quando o Tor está lento. */
    fun shouldAttempt(messageId: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastAttempt[messageId] ?: 0L
        if (now - last < 15_000L) return false
        lastAttempt[messageId] = now
        return true
    }

    // ---- onion service ----

    private suspend fun ensureOnion() {
        if (onionAddress != null) return
        if (!onionMutex.tryLock()) return
        try {
            if (onionAddress != null) return
            repeat(30) {
                val runtime = TorManager.controlPort()
                if (runtime == null) { delay(500); return@repeat }
                val keyB64 = helper?.getOrCreateOnionKeyB64() ?: return
                val key = try {
                    keyB64.toED25519_V3PrivateKey()
                } catch (t: Throwable) {
                    Log.e(TAG, "chave onion inválida: ${t.message}")
                    return
                }
                // O endereço deriva da chave; serve de fallback se o Tor disser que o
                // serviço já está registado (arranque duplicado do manager).
                val derived = key.generatePublicKey().address().toString()
                try {
                    val entry = suspendCancellableCoroutine { cont ->
                        runtime.enqueue(
                            cmd = TorCmd.Onion.Add.existing(key) { port(PORT.toPort()) },
                            onFailure = { t -> if (cont.isActive) cont.resumeWithException(t) },
                            onSuccess = { e -> if (cont.isActive) cont.resume(e) },
                        )
                    }
                    val addr = (entry as HiddenServiceEntry).publicKey.address().toString()
                    publishOnion(addr)
                    return
                } catch (t: Throwable) {
                    val msg = t.message ?: ""
                    if (msg.contains("collision", ignoreCase = true)) {
                        Log.w(TAG, "ADD_ONION colidiu; a assumir serviço já registado: $derived")
                        publishOnion(derived)
                        return
                    }
                    Log.w(TAG, "ADD_ONION falhou: ${msg.replace('\n', ' ').take(120)}; retry")
                    delay(2000)
                }
            }
        } finally {
            onionMutex.unlock()
        }
    }

    private fun publishOnion(addr: String) {
        onionAddress = addr
        _address.value = addr
        Log.i(TAG, "onion service pronto: $addr")
        try {
            descriptorJson()?.let { onDescriptorReady?.invoke(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "descritor não publicado: ${t.message}", t)
        }
    }

    // ---- listener ----

    private fun startListener() {
        if (listener != null) return
        try {
            val socket = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
            listener = socket
            listenerJob = scope.launch {
                while (isActive) {
                    val client = try { socket.accept() } catch (t: Throwable) {
                        if (isActive) Log.w(TAG, "accept terminou: ${t.message}")
                        break
                    }
                    launch(Dispatchers.IO) { handleIncoming(client) }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "listener :$PORT falhou: ${t.message}", t)
        }
    }

    private fun handleIncoming(client: Socket) {
        try {
            client.soTimeout = 45_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))

            val hello = readObject(reader) ?: return
            if (hello["t"]?.jsonPrimitive?.content != "hello") return
            val from = hello["from"]?.jsonPrimitive?.content ?: return
            val to = hello["to"]?.jsonPrimitive?.content ?: return
            val nonce = hello["nonce"]?.jsonPrimitive?.content ?: return
            val ts = hello["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
            val sig = hello["sig"]?.jsonPrimitive?.content ?: return
            val version = hello["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1

            if (to != myUid) return
            if (System.currentTimeMillis() - ts !in -60_000L..300_000L) return
            val desc = PeerDirectory.get(from) ?: run {
                Log.w(TAG, "hello de $from sem descritor — rejeitado")
                return
            }
            if (!P2PCrypto.verify("hello\n$from\n$to\n$nonce\n$ts".toByteArray(), sig, desc.signing)) {
                Log.w(TAG, "hello de $from com assinatura inválida — rejeitado")
                return
            }

            // client-auth v3. Com PSK, um hello v3 tem de provar a chave. Um hello legacy só
            // passa enquanto o par está PENDING (transição do 126 para o 127).
            val psk = PairKeyStore.psk(from)
            val pairState = PairKeyStore.state(from)
            var proofOk = false
            if (psk != null && (version >= 3 || pairState == PairState.VERIFIED)) {
                val proof = hello["proof"]?.jsonPrimitive?.content
                if (proof == null || !P2PCrypto.verifyHmac(psk, helloV3(from, to, nonce, ts), proof)) {
                    Log.w(TAG, "hello de $from sem prova da chave de par — rejeitado")
                    return
                }
                proofOk = true
            } else if (version >= 3) {
                Log.w(TAG, "hello v$version de $from sem chave de par — rejeitado")
                return
            }

            val mySig = helper!!.signP2P("hello_ok\n$nonce".toByteArray())
            writeObject(writer, buildJsonObject {
                put("t", "hello_ok"); put("nonce", nonce); put("sig", mySig)
                if (psk != null) {
                    put("v", 3)
                    put("proof", P2PCrypto.hmacB64(psk, helloOkV3(nonce)))
                }
            })
            if (proofOk) PairKeyStore.markVerified(from)
            Log.i(TAG, "ligação P2P de $from autenticada")

            while (true) {
                val frame = readObject(reader) ?: break
                when (frame["t"]?.jsonPrimitive?.content) {
                    "msg" -> {
                        val env = frame["env"]?.jsonObject ?: continue
                        val id = acceptEnvelope(env)
                        // Só confirmar depois de verificar, decifrar e guardar. Sem ack, o
                        // sender mantém a mensagem na fila e retenta.
                        if (id != null) {
                            writeObject(writer, buildJsonObject {
                                put("t", "ack"); put("id", id)
                            })
                        }
                    }
                    "ping" -> writeObject(writer, buildJsonObject { put("t", "pong") })
                    else -> {}
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ligação P2P terminou: ${t.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Verifica a assinatura do sender e decifra. Devolve o id (para o ack) ou null.
     */
    private fun acceptEnvelope(env: kotlinx.serialization.json.JsonObject): String? {
        val id = env["id"]?.jsonPrimitive?.content ?: return null
        val from = env["from"]?.jsonPrimitive?.content ?: return null
        val to = env["to"]?.jsonPrimitive?.content ?: return null
        val ts = env["ts"]?.jsonPrimitive?.content ?: return null
        val eph = env["eph"]?.jsonPrimitive?.content ?: return null
        val iv = env["iv"]?.jsonPrimitive?.content ?: return null
        val ct = env["ct"]?.jsonPrimitive?.content ?: return null
        val sig = env["sig"]?.jsonPrimitive?.content ?: return null
        val version = env["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val type = env["type"]?.jsonPrimitive?.content ?: "chat"

        if (to != myUid) return null
        val desc = PeerDirectory.get(from) ?: return null
        val psk = PairKeyStore.psk(from)
        if (version >= 3 && psk == null) {
            Log.w(TAG, "envelope v$version de $from sem chave de par — rejeitado")
            return null
        }
        if (version < 3 && psk != null && PairKeyStore.isVerified(from)) {
            Log.w(TAG, "envelope legacy de $from com par verificado — rejeitado (downgrade)")
            return null
        }
        val canonical = envelopeCanonical(
            version, type, id, from, to, ts, eph, iv, ct,
        )
        if (!P2PCrypto.verify(canonical.toByteArray(), sig, desc.signing)) {
            Log.w(TAG, "envelope de $from com assinatura inválida")
            return null
        }
        if (seenIds.contains(id) || MessageRepository.messages.value.any { it.id == id }) {
            return id // já entregue: re-ack (dedup)
        }

        val plaintext = try {
            P2PCrypto.decryptFrom(
                helper!!.getPrivateKey() ?: return null,
                eph, iv, ct,
                if (version >= 3) psk else null,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "não decifrou envelope de $from: ${t.message}")
            return null
        }
        if (version >= 3) PairKeyStore.markVerified(from)

        // Controlo: o emissor original apaga a mensagem para os dois.
        if (type == "delete") {
            val targetId = String(plaintext, Charsets.UTF_8).trim()
            val target = MessageRepository.messages.value.firstOrNull { it.id == targetId }
            if (target != null && target.senderId == from) {
                MessageRepository.removeMessage(targetId)
                Log.i(TAG, "mensagem $targetId apagada pelo emissor $from")
            }
            return id
        }

        val display = CryptoManager.encrypt(plaintext, helper!!.getOrCreateLocalKey())
        val msg = Message(
            id = id,
            senderId = from,
            receiverId = to,
            ciphertext = display,
            timestamp = ts.toLongOrNull() ?: System.currentTimeMillis(),
            delivered = true,
            deliveredAt = System.currentTimeMillis(),
        )
        MessageRepository.addMessage(msg)
        markSeen(id)
        appContext?.let { ctx ->
            val name = helper?.getSuggestedName(from).orEmpty().ifBlank { from }
            NotificationHelper.onMessageReceived(ctx, from, name, String(plaintext, Charsets.UTF_8))
        }
        Log.i(TAG, "mensagem P2P recebida de $from (${plaintext.size} B)")
        return id
    }

    private fun markSeen(id: String) {
        synchronized(seenIds) {
            seenIds.add(id)
            // Janela de dedup limitada: perder histórico antigo é aceitável (as mensagens
            // vivem 5 min em RAM).
            if (seenIds.size > 1000) seenIds.clear()
        }
    }

    // ---- envio ----

    /**
     * Entrega uma mensagem do outbox por P2P. O `msg.ciphertext` é o texto local
     * (chave de display); aqui é decifrado e re-cifrado para o par.
     * @return true só depois do ack do destinatário.
     */
    suspend fun deliver(msg: Message): Boolean {
        val peer = msg.receiverId
        val lock = peerLocks.getOrPut(peer) { Mutex() }
        return lock.withLock {
            if ((peerCooldownUntil[peer] ?: 0L) > System.currentTimeMillis()) {
                return@withLock false
            }
            val ok = deliverLocked(msg)
            if (ok) {
                peerFailures.remove(peer)
                peerCooldownUntil.remove(peer)
            } else {
                val n = (peerFailures[peer] ?: 0) + 1
                peerFailures[peer] = n
                val wait = (30_000L * (1L shl (n - 1).coerceAtMost(3))).coerceAtMost(240_000L)
                peerCooldownUntil[peer] = System.currentTimeMillis() + wait
                Log.w(TAG, "cooldown $peer: ${wait / 1000}s após $n falha(s)")
            }
            ok
        }
    }

    private suspend fun deliverLocked(msg: Message): Boolean {
        val desc = PeerDirectory.get(msg.receiverId) ?: return false
        val ks = helper ?: return false
        val plaintext = try {
            String(CryptoManager.decrypt(msg.ciphertext, ks.getOrCreateLocalKey()), Charsets.UTF_8)
        } catch (_: Throwable) {
            return false
        }
        val psk = PairKeyStore.psk(msg.receiverId)
        val envelopeV3 = psk != null && PairKeyStore.isVerified(msg.receiverId)
        val (eph, iv, ct) = P2PCrypto.encryptTo(
            desc.x25519,
            plaintext.toByteArray(),
            if (envelopeV3) psk else null,
        )
        val canonical = envelopeCanonical(
            if (envelopeV3) 3 else 1,
            msg.type,
            msg.id, msg.senderId, msg.receiverId, msg.timestamp.toString(), eph, iv, ct,
        )
        val sig = ks.signP2P(canonical.toByteArray())

        val socksPort = TorManager.socksPort.value ?: return false
        var socket: Socket? = null
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            socket = Socket(proxy)
            socket.connect(InetSocketAddress.createUnresolved(desc.onion, PORT), 75_000)
            socket.soTimeout = 75_000
            Log.i(TAG, "ligado a ${desc.onion.take(12)}… (${msg.id.take(8)})")
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            val nonce = java.util.UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val helloCanonical = "hello\n${msg.senderId}\n${msg.receiverId}\n$nonce\n$now"
            writeObject(writer, buildJsonObject {
                put("t", "hello"); put("from", msg.senderId); put("to", msg.receiverId)
                put("nonce", nonce); put("ts", now.toString())
                put("sig", ks.signP2P(helloCanonical.toByteArray()))
                if (psk != null) {
                    put("v", 3)
                    put("proof", P2PCrypto.hmacB64(
                        psk,
                        helloV3(msg.senderId, msg.receiverId, nonce, now),
                    ))
                }
            })
            val ok = readObject(reader) ?: return false
            if (ok["t"]?.jsonPrimitive?.content != "hello_ok") {
                Log.w(TAG, "hello_ok inesperado: ${ok.toString().take(80)}")
                return false
            }
            if (ok["nonce"]?.jsonPrimitive?.content != nonce) {
                Log.w(TAG, "hello_ok com nonce errado de ${msg.receiverId}")
                return false
            }
            if (psk != null) {
                val proof = ok["proof"]?.jsonPrimitive?.content
                if (proof == null || !P2PCrypto.verifyHmac(psk, helloOkV3(nonce), proof)) {
                    Log.w(TAG, "hello_ok de ${msg.receiverId} sem prova da chave de par")
                    return false
                }
            }
            val okSig = ok["sig"]?.jsonPrimitive?.content
            if (okSig != null &&
                !P2PCrypto.verify("hello_ok\n$nonce".toByteArray(), okSig, desc.signing)
            ) {
                Log.w(TAG, "hello_ok de ${msg.receiverId} com assinatura inválida")
                return false
            }
            if (psk != null) PairKeyStore.markVerified(msg.receiverId)
            Log.i(TAG, "hello_ok de ${msg.receiverId} (${msg.id.take(8)})")

            writeObject(writer, buildJsonObject {
                put("t", "msg")
                put("env", buildJsonObject {
                    put("id", msg.id); put("from", msg.senderId); put("to", msg.receiverId)
                    put("ts", msg.timestamp.toString()); put("eph", eph); put("iv", iv); put("ct", ct)
                    put("sig", sig)
                    if (envelopeV3) put("v", 3)
                    if (msg.type != "chat") put("type", msg.type)
                })
            })
            Log.i(TAG, "msg enviada (${msg.id.take(8)}), à espera do ack")
            val ack = readObject(reader) ?: return false
            val ackId = ack["id"]?.jsonPrimitive?.content
            val success = ack["t"]?.jsonPrimitive?.content == "ack" && ackId == msg.id
            if (success) Log.i(TAG, "mensagem entregue P2P a ${msg.receiverId}")
            success
        } catch (t: Throwable) {
            Log.w(TAG, "envio P2P para ${msg.receiverId} falhou: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // ---- client-auth v3: QR de par ----

    /**
     * Emite o QR de par para [peerUid] (lado que concede o acesso). Cria uma PSK nova e
     * guarda-a PENDING; devolve o payload `OGPAIR:v3:...`.
     * Voltar a chamar para o mesmo uid **substitui** a chave (re-pair).
     */
    fun createPairInvite(peerUid: String): String? {
        val me = myUid.ifBlank { helper?.getUserId() ?: return null }
        if (peerUid.isBlank() || peerUid == me) return null
        val onion = onionAddress ?: return null
        val x25519 = helper?.getPublicKey()?.let {
            android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
        } ?: return null
        val signing = helper?.signingPublicB64() ?: return null
        val psk = PairKeyStore.newPsk()
        val payload = QrPairing.encode(me, peerUid, onion, x25519, signing, psk) ?: return null
        if (!PairKeyStore.putPending(peerUid, psk)) return null
        Log.i(TAG, "QR de par emitido para $peerUid")
        return payload
    }

    /**
     * Lê um QR de par. Fixa a identidade do emissor e guarda a PSK; devolve `null` em caso de
     * sucesso ou uma razão legível em caso de erro. A verificação termina no [pairPing].
     */
    fun acceptPairInvite(payload: String): String? {
        val me = myUid.ifBlank { helper?.getUserId() ?: return "not ready" }
        return when (val scan = QrPairing.decode(payload, me)) {
            is QrPairing.Scan.Error -> scan.reason
            is QrPairing.Scan.Ok -> {
                val invite = scan.invite
                if (!PairKeyStore.putPending(invite.from, scan.psk)) {
                    return "could not store the pair key"
                }
                if (!PeerDirectory.pin(
                        invite.from, invite.onion, invite.x25519, invite.signing,
                    )
                ) {
                    PairKeyStore.remove(invite.from)
                    return "identity conflict for this contact"
                }
                pairPing(invite.from)
                null
            }
        }
    }

    /** Liga ao par só para trocar o hello com prova. Verifica os dois sentidos. */
    fun pairPing(uid: String) {
        if (uid.isBlank() || uid == myUid) return
        scope.launch { pairPingLocked(uid) }
    }

    private suspend fun pairPingLocked(uid: String) {
        val desc = PeerDirectory.get(uid) ?: return
        val psk = PairKeyStore.psk(uid) ?: return
        val ks = helper ?: return
        val socksPort = TorManager.socksPort.value ?: return
        var socket: Socket? = null
        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            socket = Socket(proxy)
            socket.connect(InetSocketAddress.createUnresolved(desc.onion, PORT), 60_000)
            socket.soTimeout = 60_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            val nonce = java.util.UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            writeObject(writer, buildJsonObject {
                put("t", "hello"); put("from", myUid); put("to", uid)
                put("nonce", nonce); put("ts", now.toString())
                put("sig", ks.signP2P("hello\n$myUid\n$uid\n$nonce\n$now".toByteArray()))
                put("v", 3)
                put("proof", P2PCrypto.hmacB64(psk, helloV3(myUid, uid, nonce, now)))
            })
            val ok = readObject(reader) ?: return
            if (ok["t"]?.jsonPrimitive?.content != "hello_ok") return
            if (ok["nonce"]?.jsonPrimitive?.content != nonce) return
            val proof = ok["proof"]?.jsonPrimitive?.content ?: return
            if (!P2PCrypto.verifyHmac(psk, helloOkV3(nonce), proof)) {
                Log.w(TAG, "pair ping $uid: prova inválida")
                return
            }
            val sig = ok["sig"]?.jsonPrimitive?.content
            if (sig != null &&
                !P2PCrypto.verify("hello_ok\n$nonce".toByteArray(), sig, desc.signing)
            ) {
                Log.w(TAG, "pair ping $uid: assinatura inválida")
                return
            }
            PairKeyStore.markVerified(uid)
            Log.i(TAG, "par verificado com $uid")
        } catch (t: Throwable) {
            Log.w(TAG, "pair ping $uid falhou: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // ---- helpers ----

    private fun helloV3(from: String, to: String, nonce: String, ts: Long): ByteArray =
        "OGHELLO-v3\n$from\n$to\n$nonce\n$ts".toByteArray()

    private fun helloOkV3(nonce: String): ByteArray =
        "OGHELLO_OK-v3\n$nonce".toByteArray()

    /**
     * Canónico do envelope. v3 (e controlos) autenticam também a versão e o tipo;
     * o chat legacy 126 mantém o formato antigo.
     */
    private fun envelopeCanonical(
        version: Int,
        type: String,
        id: String,
        from: String,
        to: String,
        ts: String,
        eph: String,
        iv: String,
        ct: String,
    ): String = if (version >= 3 || type != "chat") {
        "$version\n$type\n$id\n$from\n$to\n$ts\n$eph\n$iv\n$ct"
    } else {
        "$id\n$from\n$to\n$ts\n$eph\n$iv\n$ct"
    }

    private fun readObject(reader: BufferedReader): kotlinx.serialization.json.JsonObject? {
        val line = reader.readLine() ?: return null
        if (line.isBlank()) return null
        return try { json.parseToJsonElement(line).jsonObject } catch (_: Throwable) { null }
    }

    private fun writeObject(writer: BufferedWriter, obj: kotlinx.serialization.json.JsonObject) {
        writer.write(obj.toString())
        writer.write("\n")
        writer.flush()
    }
}
