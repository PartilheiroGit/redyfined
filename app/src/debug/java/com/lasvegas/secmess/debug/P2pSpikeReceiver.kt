package com.lasvegas.secmess.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lasvegas.secmess.network.TorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Gatilhos do spike P2P via adb (apenas builds de debug):
 *
 *   am broadcast -n com.lasvegas.secmess/.debug.P2pSpikeReceiver \
 *      -a com.lasvegas.secmess.SPIKE_START
 *   am broadcast -n com.lasvegas.secmess/.debug.P2pSpikeReceiver \
 *      -a com.lasvegas.secmess.SPIKE_PING --es onion <addr>.onion
 *   am broadcast -n com.lasvegas.secmess/.debug.P2pSpikeReceiver \
 *      -a com.lasvegas.secmess.SPIKE_STOP
 */
class P2pSpikeReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> P2pSpike.start(context.applicationContext)
            ACTION_STOP -> P2pSpike.stop()
            ACTION_PING -> {
                val onion = intent.getStringExtra("onion") ?: return
                ping(context.applicationContext, onion)
            }
        }
    }

    /** Liga ao onion indicado através do SOCKS embebido e regista a resposta. */
    private fun ping(context: Context, onion: String) {
        scope.launch {
            TorManager.start(context)
            repeat(120) {
                if (TorManager.socksPort.value != null) return@repeat
                delay(500)
            }
            val socks = TorManager.socksPort.value ?: run {
                Log.e("OGSpike", "PING sem SOCKS")
                return@launch
            }
            val started = System.currentTimeMillis()
            try {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks))
                Socket(proxy).use { socket ->
                    socket.connect(InetSocketAddress.createUnresolved(onion, P2pSpike.LOCAL_PORT), 120_000)
                    socket.soTimeout = 120_000
                    socket.getOutputStream().write("GET /ping HTTP/1.0\r\n\r\n".toByteArray())
                    socket.getOutputStream().flush()
                    val answer = socket.getInputStream().bufferedReader().readText()
                    Log.i("OGSpike", "PING ok em ${System.currentTimeMillis() - started} ms: ${answer.take(200)}")
                }
            } catch (t: Throwable) {
                Log.e("OGSpike", "PING falhou em ${System.currentTimeMillis() - started} ms: ${t.message}", t)
            }
        }
    }

    companion object {
        const val ACTION_START = "com.lasvegas.secmess.SPIKE_START"
        const val ACTION_STOP = "com.lasvegas.secmess.SPIKE_STOP"
        const val ACTION_PING = "com.lasvegas.secmess.SPIKE_PING"
    }
}
