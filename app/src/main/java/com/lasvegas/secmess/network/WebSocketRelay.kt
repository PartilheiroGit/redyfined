package com.lasvegas.secmess.network

import com.lasvegas.secmess.model.Message
import com.lasvegas.secmess.repository.MessageRepository
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.header
import io.ktor.client.plugins.DefaultRequest
import io.ktor.serialization.kotlinx.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebSocketRelay(
    private val serverUrl: String,
    private val userId: String
) {
    private val client = HttpClient(OkHttp) {
        engine {
            config {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })
                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, SecureRandom())
                sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                hostnameVerifier { _, _ -> true }
                // VPNs frequently break IPv6 / mis-resolve hostnames (same class of failure
                // that hit the server's broken IPv6 route). Prefer IPv4 to dodge it.
                dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<java.net.InetAddress> {
                        val all = okhttp3.Dns.SYSTEM.lookup(hostname)
                        val v4 = all.filterIsInstance<java.net.Inet4Address>()
                        return if (v4.isNotEmpty()) v4 else all
                    }
                })
                // Keep the socket alive across flaky VPN/NAT; detect drops without tripping Tor latency.
                pingInterval(60, java.util.concurrent.TimeUnit.SECONDS)
                connectTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                // Route .onion requests through the embedded Tor SOCKS proxy.
                if (serverUrl.contains(".onion")) {
                    try {
                        val port = TorManager.socksPort.value ?: 9050
                        val proxy = java.net.Proxy(
                            java.net.Proxy.Type.SOCKS,
                            java.net.InetSocketAddress("127.0.0.1", port),
                        )
                        proxy(proxy)
                    } catch (e: Exception) {
                        android.util.Log.w("WebSocketRelay", "Failed to configure SOCKS proxy: ${e.message}")
                    }
                }
            }
        }
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(Json { ignoreUnknownKeys = true })
        }
        install(DefaultRequest) {
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }   // tolerate older/newer message shapes
    private var prefs: com.lasvegas.secmess.crypto.KeyStoreHelper? = null

    private val _presenceMap = MutableStateFlow<Map<String, Long>>(emptyMap())
    val presenceMap = _presenceMap.asStateFlow()
    
    private val _userStatusMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val userStatusMap = _userStatusMap.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus = _connectionStatus.asStateFlow()

    fun connect(context: android.content.Context) {
        scope.launch {
            if (prefs == null) prefs = com.lasvegas.secmess.crypto.KeyStoreHelper(context)
            var currentUrl = serverUrl
            var fallbackAttempted = false
            while (isActive) {
                try {
                    _connectionStatus.value = "Connecting..."
                    client.webSocket(urlString = currentUrl) {
                        session = this
                        _connectionStatus.value = "Connected"

                        // Prove the admin session to the server (if any) before any other traffic.
                        try {
                            val sessionJson = "{\"deviceId\":\"" + (prefs?.deviceId() ?: "") +
                                "\",\"session\":\"" + (prefs?.getAdminSession() ?: "") + "\"}"
                            val hello = Message(
                                id = "hello_" + System.currentTimeMillis(),
                                senderId = userId,
                                receiverId = "SYSTEM",
                                ciphertext = sessionJson,
                                timestamp = System.currentTimeMillis(),
                                type = "hello",
                                versionCode = com.lasvegas.secmess.BuildConfig.VERSION_CODE
                            )
                            session?.send(Json.encodeToString(Message.serializer(), hello))
                        } catch (_: Exception) {}

                        // A scanned admin QR may have arrived while Tor was down: retry it now.
                        try {
                            val pendingTok = prefs?.getPendingAdminClaim() ?: ""
                            val pendingAt = prefs?.getPendingAdminClaimAt() ?: 0L
                            if (pendingTok.isNotEmpty()) {
                                if (System.currentTimeMillis() - pendingAt <= 600_000L) sendAdminClaim(pendingTok)
                                else prefs?.clearPendingAdminClaim()
                            }
                        } catch (_: Exception) {}

                        // P2P (111): publicar o meu descritor e pedir os dos pares aprovados.
                        try {
                            val desc = com.lasvegas.secmess.network.P2PManager.descriptorJson()
                            android.util.Log.i("OGP2P", "descriptor no connect: " + (desc?.take(60) ?: "null"))
                            // Envio direto (suspend) para garantir que chega DEPOIS do hello;
                            // via sendMessage() a corrotina podia disparar antes do hello e o
                            // servidor ainda não conhecer o uid.
                            if (desc != null) {
                                sendNow(Message(
                                    id = "pd_" + System.currentTimeMillis(),
                                    senderId = userId,
                                    receiverId = "SYSTEM",
                                    ciphertext = desc,
                                    timestamp = System.currentTimeMillis(),
                                    type = "p2p_desc",
                                ))
                            }
                            sendNow(Message(
                                id = "pdg_" + System.currentTimeMillis(),
                                senderId = userId,
                                receiverId = "SYSTEM",
                                ciphertext = "",
                                timestamp = System.currentTimeMillis(),
                                type = "p2p_desc_get"
                            ))
                            // Mailbox (128): puxar o que ficou para mim enquanto estava offline.
                            sendNow(Message(
                                id = "mg_" + System.currentTimeMillis(),
                                senderId = userId,
                                receiverId = "SYSTEM",
                                ciphertext = "",
                                timestamp = System.currentTimeMillis(),
                                type = "mailbox_get"
                            ))
                        } catch (_: Exception) {}

                        // Re-assert my blocks peer-to-peer (nothing is stored on the relay): tell each
                        // blocked phone again to hide me, so it survives their reinstall/cache-clear.
                        try {
                            prefs?.getBlockedUsers()?.forEach { sendPeerBlock(it, true) }
                        } catch (_: Exception) {}

                        val hbJob = launch {
                            while (isActive) {
                                sendPresenceHeartbeat(context)
                                delay(10000)
                            }
                        }

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    val msg = json.decodeFromString<Message>(text)
                                    // Encrypted peer block/unblock directive — process FIRST (must not be
                                    // dropped by the block filter). The admin ignores it (keeps oversight).
                                    if (msg.type == "peer_ctl") {
                                        if (msg.receiverId == userId && userId != "admin_001") {
                                            try {
                                                val key = "og-relay-discovery-key-32-bytes!".toByteArray().copyOf(32)
                                                val action = String(com.lasvegas.secmess.crypto.CryptoManager.decrypt(msg.ciphertext, key)).trim()
                                                if (action == "BLOCK") com.lasvegas.secmess.repository.BlockRepository.onBlockedBy(msg.senderId)
                                                else if (action == "UNBLOCK") com.lasvegas.secmess.repository.BlockRepository.onUnblockedBy(msg.senderId)
                                            } catch (_: Exception) {}
                                        }
                                        continue
                                    }
                                    // Locally drop anything from someone I've blocked OR who blocked me.
                                    // The admin and SYSTEM are never hidden (oversight + system notices).
                                    if (msg.senderId != userId && msg.senderId != "admin_001" && msg.senderId != "SYSTEM" &&
                                        (prefs?.isUserBlocked(msg.senderId) == true || prefs?.isHiddenBy(msg.senderId) == true)) continue
                                    when (msg.type) {
                                        "presence" -> {
                                            _presenceMap.update { it + (msg.senderId to System.currentTimeMillis()) }
                                            _userStatusMap.update { it + (msg.senderId to msg.ciphertext) }
                                            if (msg.nickname.isNotBlank() && prefs?.getSuggestedName(msg.senderId) != msg.nickname) prefs?.setSuggestedName(msg.senderId, msg.nickname)
                                        }
                                        "chat" -> {
                                            if ((msg.receiverId == userId) || (msg.receiverId == "broadcast")) {
                                                // Dedup: o sender retenta até ter ack, por isso o mesmo id
                                                // pode chegar mais de uma vez. Guardar só a primeira vez.
                                                val isNew = MessageRepository.messages.value.none { it.id == msg.id }
                                                if (isNew) {
                                                    // Mensagem atrasada (fila): o TTL de 5 min conta
                                                    // a partir da entrega, não do timestamp do sender.
                                                    MessageRepository.addMessage(
                                                        if (msg.senderId != userId) msg.copy(
                                                            delivered = true,
                                                            deliveredAt = System.currentTimeMillis(),
                                                        ) else msg
                                                    )
                                                }
                                                if (msg.senderId != userId) {
                                                    // Confirmação de entrega: só agora o sender tira da fila.
                                                    sendMessage(Message(
                                                        id = "da_" + msg.id,
                                                        senderId = userId,
                                                        receiverId = msg.senderId,
                                                        ciphertext = msg.id,
                                                        timestamp = System.currentTimeMillis(),
                                                        type = "delivery_ack",
                                                    ))
                                                    if (isNew) {
                                                        val preview = try {
                                                            val key = com.lasvegas.secmess.crypto.KeyStoreHelper(context).getOrCreateLocalKey()
                                                            String(com.lasvegas.secmess.crypto.CryptoManager.decrypt(msg.ciphertext, key))
                                                        } catch (_: Exception) { msg.ciphertext }
                                                        val name = msg.nickname.ifBlank {
                                                            prefs?.getSuggestedName(msg.senderId).orEmpty()
                                                        }
                                                        com.lasvegas.secmess.util.NotificationHelper.onMessageReceived(
                                                            context, msg.senderId, name, preview,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        "p2p_relay" -> {
                                            if (msg.receiverId == userId) {
                                                val id = P2PManager.acceptRelayEnvelope(context, msg.ciphertext)
                                                if (id != null) {
                                                    sendMessage(Message(
                                                        id = "da_" + id,
                                                        senderId = userId,
                                                        receiverId = msg.senderId,
                                                        ciphertext = id,
                                                        timestamp = System.currentTimeMillis(),
                                                        type = "delivery_ack",
                                                    ))
                                                }
                                            }
                                        }
                                        "delivery_ack" -> {
                                            if (msg.receiverId == userId) {
                                                com.lasvegas.secmess.repository.OutboundQueue.remove(msg.ciphertext)
                                                MessageRepository.markAsDelivered(msg.ciphertext)
                                                com.lasvegas.secmess.repository.BroadcastRepository.onAck(msg.ciphertext)
                                            }
                                        }
                                        "command" -> {
                                            if (msg.receiverId == userId || msg.receiverId == "broadcast") {
                                                handleCommand(context, msg)
                                            }
                                        }
                                        "roster" -> {
                                            try {
                                                val arr = json.parseToJsonElement(msg.ciphertext.ifBlank { "[]" }).jsonArray
                                                val pres = mutableMapOf<String, Long>()
                                                val stat = mutableMapOf<String, String>()
                                                val now = System.currentTimeMillis()
                                                for (el in arr) {
                                                    val o = el.jsonObject
                                                    val id = o["id"]?.jsonPrimitive?.content ?: continue
                                                    val st = o["status"]?.jsonPrimitive?.content ?: "DORMANT"
                                                    val nm = o["name"]?.jsonPrimitive?.content ?: ""
                                                    if (nm.isNotBlank()) prefs?.setSuggestedName(id, nm)
                                                    pres[id] = if (st == "ONLINE") now else 0L
                                                    stat[id] = st
                                                }
                                                // Replace, do not merge: deleted/archived identities disappear.
                                                _presenceMap.value = pres
                                                _userStatusMap.value = stat
                                                // Um device aprovado enquanto já estava ligado perdeu o
                                                // `p2p_desc_get` inicial (ainda não era allowed). Sem
                                                // descritor do admin, o sentido user->admin cai para o
                                                // relay e não decifra. Pedir de novo quando falta algum.
                                                if (pres.keys.any { it != userId && PeerDirectory.get(it) == null }) {
                                                    sendMessage(Message(
                                                        id = "pdg_" + System.currentTimeMillis(),
                                                        senderId = userId,
                                                        receiverId = "SYSTEM",
                                                        ciphertext = "",
                                                        timestamp = System.currentTimeMillis(),
                                                        type = "p2p_desc_get",
                                                    ))
                                                }
                                            } catch (_: Exception) {}
                                        }
                                        "download_limit" -> {
                                            com.lasvegas.secmess.repository.ServerSettingsRepository
                                                .setDownloadMax(msg.ciphertext.trim().toIntOrNull())
                                        }
                                        "subadmins" -> {
                                            com.lasvegas.secmess.repository.SubAdminRepository.apply(msg.ciphertext)
                                        }
                                        "broadcast_targets" -> {
                                            handleBroadcastTargets(context, msg.ciphertext)
                                        }
                                        "role" -> {
                                            // O servidor é a autoridade: promove/rebaixa este device.
                                            val role = msg.ciphertext.trim()
                                            val helper = com.lasvegas.secmess.crypto.KeyStoreHelper(context)
                                            if (role == "sa") helper.becomeSubAdmin()
                                            else helper.setTier(com.lasvegas.secmess.crypto.UserTier.FIELD_USER)
                                            Handler(Looper.getMainLooper()).post {
                                                Toast.makeText(
                                                    context,
                                                    if (role == "sa") "Sub-admin ativado" else "Voltaste a user",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                        "unregistered" -> {
                                            prefs?.clearAdminSession()
                                            Handler(Looper.getMainLooper()).post {
                                                Toast.makeText(context, "Device removed from the server. You can uninstall.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        "admin_granted" -> {
                                            val sess = msg.ciphertext
                                            if (sess.isNotBlank()) {
                                                prefs?.becomeAdmin(sess)
                                                prefs?.clearPendingAdminClaim()
                                                Handler(Looper.getMainLooper()).post {
                                                    Toast.makeText(context, "Admin grant received — reopen the app to apply.", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                        "admin_claim_denied" -> {
                                            prefs?.clearPendingAdminClaim()
                                            Handler(Looper.getMainLooper()).post {
                                                Toast.makeText(context, "Admin QR rejected or expired — ask the server for a new one.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        "admin_state" -> {
                                            val myDev = prefs?.deviceId() ?: ""
                                            if (msg.nickname == "revoked") {
                                                prefs?.clearPendingAdminClaim()
                                                prefs?.setAdminRevoked(true)
                                                Handler(Looper.getMainLooper()).post {
                                                    Toast.makeText(context, "Admin transferred — this device is locked to the calendar.", Toast.LENGTH_LONG).show()
                                                }
                                            } else if (prefs?.isAdmin() == true) {
                                                when {
                                                    msg.nickname == "invalid" -> {
                                                        prefs?.clearAdminSession()
                                                        Handler(Looper.getMainLooper()).post {
                                                            Toast.makeText(context, "Admin session expired — scan a new server QR.", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                    msg.ciphertext.isNotBlank() && msg.ciphertext != myDev -> {
                                                        prefs?.setAdminRevoked(true)
                                                        Handler(Looper.getMainLooper()).post {
                                                            Toast.makeText(context, "Admin transferred — this device is locked to the calendar.", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        "revoke_lock" -> {
                                            val myDev = prefs?.deviceId() ?: ""
                                            if (msg.ciphertext == myDev) {
                                                prefs?.setAdminRevoked(false)
                                            } else if (prefs?.isAdmin() == true) {
                                                prefs?.clearPendingAdminClaim()
                                                prefs?.setAdminRevoked(true)
                                                Handler(Looper.getMainLooper()).post {
                                                    Toast.makeText(context, "Admin transferred — this device is locked to the calendar.", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                        "admin_claim" -> {
                                            // legacy: another device claimed admin -> if I'm the old admin, step down
                                            if (userId == "admin_001" && msg.ciphertext != (prefs?.deviceId() ?: "")) {
                                                prefs?.setAdminRevoked(true)
                                            }
                                        }
                                        "suggestion" -> {
                                            if (userId == "admin_001") {
                                                com.lasvegas.secmess.repository.SuggestionRepository.add(msg.id, msg.senderId, msg.ciphertext)
                                                com.lasvegas.secmess.util.NotificationHelper.onMessageReceived(
                                                    context, "SYSTEM", "Sistema", msg.ciphertext,
                                                )
                                            }
                                        }
                                        "join_request" -> {
                                            // server -> admin: a new device is awaiting approval
                                            if (userId == "admin_001") {
                                                com.lasvegas.secmess.repository.PendingDeviceRepository.add(msg.senderId, msg.nickname)
                                                com.lasvegas.secmess.util.NotificationHelper.onMessageReceived(
                                                    context, "SYSTEM", "Sistema",
                                                    "Novo device quer entrar: ${msg.nickname.ifBlank { msg.senderId }}",
                                                )
                                            }
                                        }
                                        "join_cleared" -> {
                                            // server -> admin: that device was approved/denied/left
                                            com.lasvegas.secmess.repository.PendingDeviceRepository.remove(msg.senderId)
                                        }
                                        "invite_url" -> {
                                            // server -> admin: one-time master-invite link to show as a QR
                                            if (msg.receiverId == userId) {
                                                com.lasvegas.secmess.repository.InviteRepository.set(msg.ciphertext)
                                            }
                                        }
                                        "read_receipt" -> {
                                            if (msg.receiverId == userId) {
                                                com.lasvegas.secmess.repository.MessageRepository.markAsReadRemote(msg.ciphertext, System.currentTimeMillis())
                                            }
                                        }
                                        "p2p_desc" -> {
                                            if (msg.senderId != userId) {
                                                try {
                                                    val d = json.parseToJsonElement(msg.ciphertext).jsonObject
                                                    PeerDirectory.upsert(
                                                        uid = msg.senderId,
                                                        onion = d["onion"]?.jsonPrimitive?.content ?: "",
                                                        x25519 = d["x25519"]?.jsonPrimitive?.content ?: "",
                                                        signing = d["signing"]?.jsonPrimitive?.content ?: "",
                                                    )
                                                } catch (_: Exception) {}
                                            }
                                        }
                                        "p2p_desc_all" -> {
                                            try {
                                                for (el in json.parseToJsonElement(msg.ciphertext).jsonArray) {
                                                    val o = el.jsonObject
                                                    val id = o["id"]?.jsonPrimitive?.content ?: continue
                                                    if (id == userId) continue
                                                    PeerDirectory.upsert(
                                                        uid = id,
                                                        onion = o["onion"]?.jsonPrimitive?.content ?: "",
                                                        x25519 = o["x25519"]?.jsonPrimitive?.content ?: "",
                                                        signing = o["signing"]?.jsonPrimitive?.content ?: "",
                                                    )
                                                }
                                            } catch (_: Exception) {}
                                        }
                                        "mailbox_batch" -> {
                                            try {
                                                val ackIds = mutableListOf<String>()
                                                for (el in json.parseToJsonElement(msg.ciphertext).jsonArray) {
                                                    val o = el.jsonObject
                                                    val id = o["id"]?.jsonPrimitive?.content ?: continue
                                                    val blob = o["blob"]?.jsonPrimitive?.content ?: continue
                                                    val delivered = P2PManager.acceptRelayEnvelope(context, blob)
                                                    if (delivered != null) ackIds.add(id)
                                                }
                                                if (ackIds.isNotEmpty()) {
                                                    val idsJson = ackIds.joinToString(
                                                        prefix = "[", postfix = "]",
                                                    ) { "\"" + it.replace("\"", "") + "\"" }
                                                    sendNow(Message(
                                                        id = "ma_" + System.currentTimeMillis(),
                                                        senderId = userId,
                                                        receiverId = "SYSTEM",
                                                        ciphertext = idsJson,
                                                        timestamp = System.currentTimeMillis(),
                                                        type = "mailbox_ack",
                                                    ))
                                                }
                                            } catch (_: Exception) {}
                                        }
                                        "mailbox_ping" -> {
                                            if (msg.receiverId == userId) {
                                                sendNow(Message(
                                                    id = "mg_" + System.currentTimeMillis(),
                                                    senderId = userId,
                                                    receiverId = "SYSTEM",
                                                    ciphertext = "",
                                                    timestamp = System.currentTimeMillis(),
                                                    type = "mailbox_get",
                                                ))
                                            }
                                        }
                                        "mailbox_put_ok" -> {
                                            val mid = msg.ciphertext.trim()
                                            if (mid.isNotEmpty()) {
                                                com.lasvegas.secmess.repository.OutboundQueue.remove(mid)
                                                com.lasvegas.secmess.repository.MessageRepository.markAsDelivered(mid)
                                                com.lasvegas.secmess.repository.BroadcastRepository.onAck(mid)
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            hbJob.cancel()
                            session = null
                            _connectionStatus.value = "Disconnected"
                        }
                    }
                } catch (e: java.net.UnknownHostException) {
                    _connectionStatus.value = "DNS Error: Check Tor/Network"
                    delay(8000)
                } catch (e: Exception) {
                    val msg = e.message ?: "Unknown"
                    if (msg.contains("101")) {
                        _connectionStatus.value = "Relay Error: Handshake Fail"
                        delay(5000)
                    } else {
                        _connectionStatus.value = "Error: ${msg.take(30)}"
                        delay(5000)
                    }
                }
            }
        }
    }

    private fun handleCommand(context: android.content.Context, msg: Message) {
        if (msg.senderId != "admin_001") return // Only Admin can issue commands

        when (msg.ciphertext) {
            "REVERT_STEALTH" -> {
                val helper = com.lasvegas.secmess.crypto.KeyStoreHelper(context)
                helper.setStealthEndTime(0) // Wipe the lockout timer
            }
        }
    }

    private suspend fun sendPresenceHeartbeat(context: android.content.Context) {
        try {
            val helper = com.lasvegas.secmess.crypto.KeyStoreHelper(context)
            val isAdmin = userId == "admin_001"
            val isUnsafe = (com.lasvegas.secmess.util.LocationMonitor.isInsideUnsafeZone.value || helper.isStealthActive()) && !isAdmin
            // ONLINE = app open (even screen off). DORMANT = app closed, service still receiving.
            val status = when {
                isUnsafe -> "UNSAFE"
                com.lasvegas.secmess.util.AppState.appOpen -> "ONLINE"
                else -> "DORMANT"
            }

            // 1. Private heartbeat to Admin (Detailed Status for Supervision)
            if (userId != "admin_001") {
                val adminHeartbeat = Message(
                    id = "hb_adm_" + System.currentTimeMillis(),
                    senderId = userId,
                    receiverId = "admin_001",
                    ciphertext = status,
                    timestamp = System.currentTimeMillis(),
                    type = "presence",
                    versionCode = com.lasvegas.secmess.BuildConfig.VERSION_CODE,
                    nickname = helper.getMyNickname()
                )
                session?.send(Json.encodeToString(Message.serializer(), adminHeartbeat))
            }

            // 2. Public heartbeat to Broadcast (Privacy: Team sees "OFFLINE" during Stealth)
            val publicHeartbeat = Message(
                id = "hb_pub_" + System.currentTimeMillis(),
                senderId = userId,
                receiverId = "broadcast",
                ciphertext = if (userId == "admin_001") status else if (isUnsafe) "OFFLINE" else status,
                timestamp = System.currentTimeMillis(),
                type = "presence",
                versionCode = com.lasvegas.secmess.BuildConfig.VERSION_CODE,
                nickname = helper.getMyNickname()
            )
            session?.send(Json.encodeToString(Message.serializer(), publicHeartbeat))
        } catch (_: Exception) {}
    }

    // Send an ENCRYPTED block/unblock directive to one peer (the relay just forwards it and learns
    // nothing — the action rides inside ciphertext). His app then hides/shows me accordingly.
    fun sendPeerBlock(targetId: String, block: Boolean) {
        if (targetId.isBlank()) return
        scope.launch {
            try {
                val key = "og-relay-discovery-key-32-bytes!".toByteArray().copyOf(32)
                val ct = com.lasvegas.secmess.crypto.CryptoManager.encrypt((if (block) "BLOCK" else "UNBLOCK").toByteArray(), key)
                val m = Message(
                    id = "pc_" + System.currentTimeMillis(), senderId = userId, receiverId = targetId,
                    ciphertext = ct, timestamp = System.currentTimeMillis(), type = "peer_ctl"
                )
                session?.send(Json.encodeToString(Message.serializer(), m))
            } catch (_: Exception) {}
        }
    }

    // Admin phone asks the relay to mint a one-time master-invite link (reply arrives as "invite_url").
    fun requestInvite() {
        scope.launch {
            try {
                val m = Message(
                    id = "mint_" + System.currentTimeMillis(), senderId = userId, receiverId = "SERVER",
                    ciphertext = "", timestamp = System.currentTimeMillis(), type = "mint_invite"
                )
                session?.send(Json.encodeToString(Message.serializer(), m))
            } catch (_: Exception) {}
        }
    }

    fun sendUnregister() {
        sendMessage(Message(
            id = "unreg_" + System.currentTimeMillis(),
            senderId = userId,
            receiverId = "SYSTEM",
            ciphertext = prefs?.deviceId() ?: "",
            timestamp = System.currentTimeMillis(),
            type = "unregister"
        ))
    }

    fun sendAdminClaim(token: String) {
        if (token.isBlank()) return
        sendMessage(Message(
            id = "claim_" + System.currentTimeMillis(),
            senderId = userId,
            receiverId = "SYSTEM",
            ciphertext = prefs?.deviceId() ?: "",
            timestamp = System.currentTimeMillis(),
            type = "admin_claim",
            nickname = token
        ))
    }

    /** Envio imediato na sessão atual (ordem garantida; usado no arranque). */
    private suspend fun sendNow(message: Message) {
        session?.send(Json.encodeToString(Message.serializer(), message))
    }

    /** Publica o descritor P2P (onion + chaves públicas) no plano de controlo. */
    fun publishDescriptor(descriptorJson: String?) {
        if (descriptorJson.isNullOrBlank()) return
        sendMessage(Message(
            id = "pd_" + System.currentTimeMillis(),
            senderId = userId,
            receiverId = "SYSTEM",
            ciphertext = descriptorJson,
            timestamp = System.currentTimeMillis(),
            type = "p2p_desc",
        ))
    }

    fun sendMessage(message: Message) {
        scope.launch {
            try {
                session?.send(Json.encodeToString(Message.serializer(), message))
            } catch (_: Exception) {}
        }
    }

    /**
     * O servidor respondeu com os ids do broadcast. Cria uma mensagem 1:1 por
     * destinatário, cifrada com a chave local (o P2P re-cifra para cada par) e
     * enfileira. O painel conta os acks via BroadcastRepository.
     */
    private fun handleBroadcastTargets(context: android.content.Context, raw: String) {
        try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val ids = obj["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
            val text = com.lasvegas.secmess.repository.BroadcastRepository.pendingText()
            if (text.isBlank()) return
            val key = com.lasvegas.secmess.crypto.KeyStoreHelper(context).getOrCreateLocalKey()
            val encrypted = com.lasvegas.secmess.crypto.CryptoManager.encrypt(text.toByteArray(), key)
            val msgs = mutableListOf<Pair<Message, String>>()
            for (target in ids.distinct()) {
                msgs += Message(
                    id = java.util.UUID.randomUUID().toString(),
                    senderId = userId,
                    receiverId = target,
                    ciphertext = encrypted,
                    timestamp = com.lasvegas.secmess.repository.MessageClock.next(),
                    type = "chat",
                ) to target
            }
            // Registar os ids antes de enfileirar: um ack muito rápido não se perde.
            com.lasvegas.secmess.repository.BroadcastRepository.sent(msgs.associate { it.first.id to it.second })
            for ((msg, _) in msgs) {
                com.lasvegas.secmess.repository.MessageRepository.addMessage(msg)
                com.lasvegas.secmess.repository.OutboundQueue.enqueue(msg)
            }
        } catch (_: Exception) {
            com.lasvegas.secmess.repository.BroadcastRepository.fail()
        }
    }

    fun sendPing(targetUserId: String) {
        scope.launch {
            try {
                val ping = Message(
                    id = "ping_" + System.currentTimeMillis(),
                    senderId = userId,
                    receiverId = targetUserId,
                    ciphertext = "",
                    timestamp = System.currentTimeMillis(),
                    type = "ping"
                )
                session?.send(Json.encodeToString(Message.serializer(), ping))
            } catch (_: Exception) {}
        }
    }

    fun reconnect() {
        scope.launch { try { session?.close() } catch (_: Exception) {} }
    }

    fun disconnect() {
        scope.cancel()
        client.close()
    }
}
