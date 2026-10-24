package com.lasvegas.secmess.debug

import android.content.Context
import android.util.Log
import com.lasvegas.secmess.network.TorManager
import io.matthewnelson.kmp.tor.runtime.core.ctrl.HiddenServiceEntry
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3.PrivateKey.Companion.toED25519_V3PrivateKey
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import io.matthewnelson.kmp.tor.runtime.core.EnqueuedJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Spike 111 fase 0: provar `ADD_ONION` no Tor embebido e entrada de ligações de fora.
 *
 * Só existe no build de debug (`app/src/debug`). Fluxo:
 *  1. arranca o Tor (TorManager) e espera pela porta SOCKS;
 *  2. cria/recupera uma chave ED25519 v3 e publica um onion service para 127.0.0.1:8087;
 *  3. abre um listener TCP que responde `OG-P2P-OK`;
 *  4. escreve o endereço em `files/p2p_spike.txt` e no logcat (`OGSpike`).
 *
 * Gatilho (adb, build de debug):
 *   adb shell am broadcast -n com.lasvegas.secmess/.debug.P2pSpikeReceiver \
 *       -a com.lasvegas.secmess.SPIKE_START
 */
object P2pSpike {
    private const val TAG = "OGSpike"
    private const val PREFS = "og_p2p_spike"
    private const val KEY_ONION_PRIVATE = "onion_private_ed25519"
    const val LOCAL_PORT = 8087

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listener: ServerSocket? = null
    private var acceptJob: Job? = null
    private var onionJob: Job? = null

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (onionJob?.isActive == true) {
            Log.i(TAG, "spike já a correr; ver p2p_spike.txt")
            return
        }
        onionJob = scope.launch {
            try {
                startListener()
                TorManager.start(app)
                waitForSocks()

                val key = loadOrCreateKey(app)
                val entry = addOnion(key)
                val address = entry.publicKey.address().toString()
                val line = "ONION=$address\nPORT=$LOCAL_PORT\nKEY=${entry.publicKey.base64()}\n"
                writeStatus(app, line.trim())
                Log.i(TAG, "ONION pronto: $address (local :$LOCAL_PORT)")
            } catch (t: Throwable) {
                Log.e(TAG, "spike falhou: ${t.message}", t)
                writeStatus(app, "ERROR=${t.message}")
            }
        }
    }

    fun stop() {
        onionJob?.cancel()
        onionJob = null
        acceptJob?.cancel()
        acceptJob = null
        try { listener?.close() } catch (_: Exception) {}
        listener = null
    }

    // --- onion service ---

    private fun loadOrCreateKey(context: Context): ED25519_V3.PrivateKey {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_ONION_PRIVATE, null)
        if (!stored.isNullOrBlank()) {
            runCatching { stored.toED25519_V3PrivateKey() }
                .onSuccess { return it }
                .onFailure { Log.w(TAG, "chave guardada inválida; a gerar nova", it) }
        }
        val fresh = ED25519_V3.PrivateKey.generate()
        prefs.edit().putString(KEY_ONION_PRIVATE, fresh.base64()).apply()
        Log.i(TAG, "nova chave onion gerada")
        return fresh
    }

    private suspend fun addOnion(key: ED25519_V3.PrivateKey): HiddenServiceEntry {
        val runtime = TorManager.controlPort() ?: error("Tor runtime indisponível")
        return suspendCancellableCoroutine { cont ->
            runtime.enqueue(
                cmd = TorCmd.Onion.Add.existing(key) {
                    port(LOCAL_PORT.toPort())
                },
                onFailure = { t ->
                    if (cont.isActive) cont.resumeWithException(t)
                },
                onSuccess = { entry ->
                    if (cont.isActive) cont.resume(entry)
                },
            )
        }
    }

    private suspend fun waitForSocks() {
        repeat(120) {
            if (TorManager.socksPort.value != null) return
            delay(500)
        }
        error("SOCKS não abriu em 60 s")
    }

    // --- listener local (o que o onion service expõe) ---

    private fun startListener() {
        if (listener != null) return
        val socket = ServerSocket(LOCAL_PORT, 16, InetAddress.getByName("127.0.0.1"))
        listener = socket
        acceptJob = scope.launch {
            Log.i(TAG, "listener 127.0.0.1:$LOCAL_PORT à espera")
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (t: Throwable) {
                    if (isActive) Log.w(TAG, "accept terminou: ${t.message}")
                    break
                }
                launch(Dispatchers.IO) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 10_000
            // Lê a primeira linha do pedido (HTTP ou texto simples); não é preciso mais.
            val line = client.getInputStream().bufferedReader().readLine() ?: ""
            val body = "OG-P2P-OK ${System.currentTimeMillis()}"
            val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n$body"
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().flush()
            Log.i(TAG, "pedido recebido: ${line.take(80)}")
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "timeout à espera do pedido")
        } catch (t: Throwable) {
            Log.w(TAG, "falha a servir: ${t.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun writeStatus(context: Context, text: String) {
        try {
            File(context.filesDir, "p2p_spike.txt").writeText(text)
            Log.i(TAG, "status: ${text.replace('\n', ' ')}")
        } catch (t: Throwable) {
            Log.w(TAG, "não escreveu p2p_spike.txt: ${t.message}")
        }
    }
}
