package com.lasvegas.secmess.network

import android.content.Context
import android.util.Log
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.config.IntervalUnit
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Ephemeral.Companion.toPortEphemeral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/**
 * Tor embebido (kmp-tor).
 *
 * Substitui a dependência de Orbot do v96. O `auto()` deixa o Tor escolher
 * a porta SOCKS; a porta real é publicada em [socksPort] e usada pelo
 * WebSocketRelay e pelo RelayService.
 */
object TorManager {
    private const val TAG = "OGTor"

    private var runtime: TorRuntime? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _socksPort = MutableStateFlow<Int?>(null)
    val socksPort: StateFlow<Int?> = _socksPort.asStateFlow()

    @Synchronized
    fun start(context: Context) {
        if (runtime != null) return

        val appContext = context.applicationContext
        val workDir = File(appContext.filesDir, "kmptor")
        val cacheDir = File(appContext.filesDir, "kmptor-cache")
        workDir.mkdirs()
        cacheDir.mkdirs()

        Log.d(TAG, "A arrancar Tor embebido...")

        try {
            val environment = TorRuntime.Environment.Builder(
                workDirectory = workDir,
                cacheDirectory = cacheDir,
                loader = ResourceLoaderTorExec.Companion::getOrCreate,
            )

            runtime = TorRuntime.Builder(environment) {
                config {
                    TorOption.__SocksPort.configure { port(9050.toPortEphemeral()) }
                    // O runtime inicia o daemon com DisableNetwork=1; forçar rede ligada.
                    TorOption.DisableNetwork.configure(false)
                    // O default é 120 s; ligações a onion podem demorar mais.
                    TorOption.SocksTimeout.configure(300, IntervalUnit.SECONDS)
                }
                observerStatic(RuntimeEvent.LISTENERS) { listeners ->
                    val port = listeners.socks.firstOrNull()?.port?.value
                    if (port != null) {
                        _socksPort.value = port
                        Log.d(TAG, "SOCKS pronto em 127.0.0.1:$port")
                    }
                }
                observerStatic(RuntimeEvent.ERROR) { t ->
                    Log.e(TAG, "Erro do Tor", t)
                }
                observerStatic(RuntimeEvent.LIFECYCLE) { event ->
                    Log.d(TAG, "Tor lifecycle: $event")
                }
                observerStatic(RuntimeEvent.LOG.WARN) { message ->
                    Log.w(TAG, message)
                }
                observerStatic(RuntimeEvent.LOG.DEBUG) { message ->
                    Log.d(TAG, message)
                }
            }

            runtime?.enqueue(
                action = Action.StartDaemon,
                onFailure = { t -> Log.e(TAG, "StartDaemon falhou", t) },
                onSuccess = { Log.d(TAG, "StartDaemon concluído") },
            )

            waitForSocksPort(9050)
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao arrancar o Tor", t)
            runtime = null
            _socksPort.value = null
        }
    }

    private fun waitForSocksPort(port: Int) {
        scope.launch {
            repeat(120) {
                if (!isActive) return@launch
                try {
                    Socket("127.0.0.1", port).use {
                        _socksPort.value = port
                        Log.d(TAG, "SOCKS disponível em 127.0.0.1:$port")
                        return@launch
                    }
                } catch (_: Exception) {
                    delay(500)
                }
            }
            Log.e(TAG, "SOCKS 127.0.0.1:$port não abriu em 60 s")
        }
    }

    /**
     * Acesso ao runtime do Tor para comandos do control-port (ex.: `ADD_ONION`).
     * O `TorRuntime` implementa `TorCmd.Unprivileged.Processor`; devolve `null` se o
     * daemon ainda não arrancou.
     */
    internal fun controlPort(): TorRuntime? = runtime

    fun stop() {
        runtime?.enqueue(
            action = Action.StopDaemon,
            onFailure = { t -> Log.e(TAG, "StopDaemon falhou", t) },
            onSuccess = { Log.d(TAG, "StopDaemon concluído") },
        )
        runtime = null
        _socksPort.value = null
    }

    /**
     * Reinicia o daemon Tor sem recriar o processo da app. Usado pelo watchdog do
     * RelayService quando o SOCKS/relay fica preso após uma mudança de rede/reboot.
     */
    @Synchronized
    fun restart(context: Context) {
        val rt = runtime
        if (rt == null) {
            start(context)
            return
        }
        Log.d(TAG, "A reiniciar Tor embebido...")
        _socksPort.value = null
        rt.enqueue(
            action = Action.RestartDaemon,
            onFailure = { t ->
                Log.e(TAG, "RestartDaemon falhou; a recriar", t)
                runtime = null
                start(context)
            },
            onSuccess = { Log.d(TAG, "RestartDaemon concluído") },
        )
        waitForSocksPort(9050)
    }

    /**
     * Abre uma ligação HTTP através do SOCKS do Tor, quando disponível.
     * Usado para os endpoints de update e para o gist de zonas.
     */
    fun openConnection(url: String): HttpURLConnection {
        val port = _socksPort.value
        val connection = if (port != null) {
            URL(url).openConnection(
                Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)),
            )
        } else {
            URL(url).openConnection()
        }
        return connection as HttpURLConnection
    }
}
