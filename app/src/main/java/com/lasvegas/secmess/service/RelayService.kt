package com.lasvegas.secmess.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lasvegas.secmess.R
import com.lasvegas.secmess.crypto.KeyStoreHelper
import com.lasvegas.secmess.network.RelayConfig
import com.lasvegas.secmess.network.P2PManager
import com.lasvegas.secmess.network.TorManager
import com.lasvegas.secmess.network.WebSocketRelay
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

class RelayService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var nextUpdateCheckAt = 0L
    private var lastConnectedAt = 0L
    private var lastResetAt = 0L
    private var reconnectAttempts = 0
    private var torStartedAt = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val relayLastSent = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var lastDescriptorAt = 0L

    companion object {
        /** Sinal de vida do serviço, lido pelo KeepAliveJobService. */
        @Volatile var isRunning = false
        @Volatile var lastAliveAt = 0L

        private val _relayState = MutableStateFlow<WebSocketRelay?>(null)
        val relayState = _relayState.asStateFlow()

        private val _currentRelayUrl = MutableStateFlow<String>("")
        val currentRelayUrl = _currentRelayUrl.asStateFlow()

        private val refreshChannel = Channel<Unit>(Channel.CONFLATED)

        /** Setado no onResume: força uma verificação de update na próxima volta do loop. */
        @Volatile private var forceUpdateCheck = false
        fun requestUpdateCheck() { forceUpdateCheck = true }
        
        // Legacy access for direct calls (refactored to point to flow)
        // val relay: WebSocketRelay? get() = _relayState.value
        
        fun start(context: Context) {
            val intent = Intent(context, RelayService::class.java)
            context.startForegroundService(intent)
        }

        fun forceDiscoveryRefresh() {
            refreshChannel.trySend(Unit)
        }

        /** Só lê a versão publicada no canal (não descarrega nada). */
        fun fetchChannelVersion(onResult: (Int?) -> Unit) {
            val relayUrl = _currentRelayUrl.value
            if (relayUrl.isBlank()) { onResult(null); return }
            val base = relayUrl.replace("wss://", "https://").replace("ws://", "http://")
            CoroutineScope(Dispatchers.IO).launch {
                val remote = try {
                    val vc = TorManager.openConnection("$base/update/version?t=${System.currentTimeMillis()}")
                    vc.connectTimeout = 10000
                    if (vc.responseCode == 200) {
                        vc.inputStream.bufferedReader().use { it.readText() }.trim().toIntOrNull()
                    } else null
                } catch (_: Exception) { null }
                withContext(Dispatchers.Main) { onResult(remote) }
            }
        }

        // On-demand update check for the Settings "CHECK FOR UPDATES" button. Uses the SAME
        // self-hosted channel as the auto-check (over the relay tunnel; no public GitHub, no token).
        fun checkForUpdate(context: Context, onStatus: (String) -> Unit) {
            val relayUrl = _currentRelayUrl.value
            if (relayUrl.isBlank()) { onStatus("Not connected yet — try again in a moment"); return }
            val base = relayUrl.replace("wss://", "https://").replace("ws://", "http://")
            CoroutineScope(Dispatchers.IO).launch {
                val status = try {
                    val vc = TorManager.openConnection("$base/update/version?t=${System.currentTimeMillis()}")
                    vc.connectTimeout = 10000
                    if (vc.responseCode == 200) {
                        val remote = vc.inputStream.bufferedReader().use { it.readText() }.trim().toIntOrNull() ?: 0
                        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        val local = if (android.os.Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode.toInt()
                                    else @Suppress("DEPRECATION") pInfo.versionCode
                        if (remote > local) {
                            if (com.lasvegas.secmess.network.UpdateManager.isDownloading()) {
                                "Download já em curso"
                            } else {
                                val opened = withContext(Dispatchers.Main) {
                                    com.lasvegas.secmess.network.UpdateManager.installCached(context, remote)
                                }
                                if (opened) {
                                    "Update ready — installer opened"
                                } else {
                                    withContext(Dispatchers.Main) {
                                        com.lasvegas.secmess.network.UpdateManager.startUpdate(context, "$base/update/app.apk")
                                    }
                                    "Update found — downloading…"
                                }
                            }
                        } else if (remote == local) {
                            "Latest — channel $remote (app $local)"
                        } else {
                            "Test build — app $local · channel $remote"
                        }
                    } else "Update check failed (${vc.responseCode})"
                } catch (_: Exception) {
                    "Update check failed — check connection"
                }
                withContext(Dispatchers.Main) { onStatus(status) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        isRunning = true
        lastAliveAt = System.currentTimeMillis()
        KeepAliveJobService.schedule(applicationContext)

        // Privacidade no ecrã bloqueado: troca o conteúdo das notificações ao bloquear/desbloquear.

        // Fila de saída persistente (12 h, até ack). Restaura o que ficou por entregar.
        com.lasvegas.secmess.repository.OutboundQueue.init(applicationContext)

        // Delete old noisy channels and recreate as silent
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel("calendar_reminders_channel")
            nm.deleteNotificationChannel("calendar_alarm_channel")
            nm.deleteNotificationChannel("relay_service_channel")
        }

        showForegroundNotification()

        // Any network change invalidates the old WebSocket/Tor circuits. Rebuild instead of
        // waiting for a stuck 300s SOCKS timeout.
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { scheduleRelayReset("network available") }
                override fun onLost(network: Network) { scheduleRelayReset("network lost") }
            }
            cm.registerDefaultNetworkCallback(networkCallback!!)
        } catch (t: Throwable) {
            android.util.Log.w("RelayService", "Network callback indisponível: ${t.message}")
        }

        
        // Outbound queue flusher: P2P direto primeiro (desvia tráfego do relay); se o par estiver
        // offline ou o P2P falhar, põe o envelope E2E no mailbox do relay (store-and-forward).
        scope.launch {
            val me = com.lasvegas.secmess.crypto.KeyStoreHelper(this@RelayService).getUserId()
            val mailboxLastSent = java.util.concurrent.ConcurrentHashMap<String, Long>()
            var lastMailboxPull = 0L
            while (isActive) {
                val relay = _relayState.value
                if (relay != null && relay.connectionStatus.value == "Connected") {
                    val presence = relay.presenceMap.value
                    val now = System.currentTimeMillis()

                    // Puxa o mailbox periodicamente; o servidor também manda um ping quando entra algo.
                    if (now - lastMailboxPull >= 30_000L) {
                        lastMailboxPull = now
                        relay.sendMessage(com.lasvegas.secmess.model.Message(
                            id = "mg_" + now, senderId = me, receiverId = "SYSTEM",
                            ciphertext = "", timestamp = now, type = "mailbox_get",
                        ))
                    }

                    fun mailboxPut(msg: com.lasvegas.secmess.model.Message) {
                        val last = mailboxLastSent[msg.id] ?: 0L
                        if (now - last < 10_000L) return
                        val env = P2PManager.relayEnvelope(msg) ?: run {
                            // Sem descritor do par: pede e tenta no próximo ciclo.
                            relay.sendMessage(com.lasvegas.secmess.model.Message(
                                id = "pdg_" + now, senderId = me, receiverId = "SYSTEM",
                                ciphertext = "", timestamp = now, type = "p2p_desc_get",
                            ))
                            return
                        }
                        relay.sendMessage(com.lasvegas.secmess.model.Message(
                            id = msg.id, senderId = msg.senderId, receiverId = msg.receiverId,
                            ciphertext = env, timestamp = now, type = "mailbox_put",
                        ))
                        mailboxLastSent[msg.id] = now
                    }

                    val queued = com.lasvegas.secmess.repository.OutboundQueue.snapshot()
                    for (msg in queued) {
                        if (msg.receiverId == "broadcast") continue
                        val recipientSeen = presence[msg.receiverId] ?: 0L
                        val recipientConnected = (now - recipientSeen) < 40000
                        // P2P (111): pares com descritor recebem direto, sem passar pelo relay.
                        if (P2PManager.canReach(msg.receiverId)) {
                            if (recipientConnected && P2PManager.shouldAttempt(msg.id) && inFlight.add(msg.id)) {
                                scope.launch {
                                    val ok = P2PManager.deliver(msg)
                                    if (ok) {
                                        com.lasvegas.secmess.repository.OutboundQueue.remove(msg.id)
                                        com.lasvegas.secmess.repository.MessageRepository.markAsDelivered(msg.id)
                                        com.lasvegas.secmess.repository.BroadcastRepository.onAck(msg.id)
                                    } else {
                                        // P2P falhou: o envelope vai para o mailbox do relay.
                                        mailboxPut(msg)
                                    }
                                    inFlight.remove(msg.id)
                                }
                            }
                        } else {
                            // Sem P2P viável: mailbox (o destinatário puxa quando voltar).
                            mailboxPut(msg)
                        }
                    }
                    mailboxLastSent.keys.retainAll(queued.map { it.id }.toSet())
                }
                delay(3000)
            }
        }

        scope.launch {
            android.util.Log.d("RelayService", "Relay coroutine START - Tor embebido (kmp-tor)")
            torStartedAt = System.currentTimeMillis()
            TorManager.start(applicationContext)
            val socksPort = withTimeoutOrNull(60_000) {
                TorManager.socksPort.filterNotNull().first()
            }
            if (socksPort == null) {
                android.util.Log.e("RelayService", "Tor não ficou pronto em 60 s; a tentar mesmo assim")
            } else {
                android.util.Log.d("RelayService", "Tor pronto; SOCKS em 127.0.0.1:$socksPort")
            }

            val keyStoreHelper = KeyStoreHelper(this@RelayService)
            val myUserId = keyStoreHelper.getUserId()

            // P2P (111): onion service + listener. O descritor é publicado no relay assim
            // que o onion existir; os pares aprovados recebem-no pelo plano de controlo.
            try {
                P2PManager.onDescriptorReady = { d -> _relayState.value?.publishDescriptor(d) }
                P2PManager.start(this@RelayService)
            } catch (t: Throwable) {
                android.util.Log.w("RelayService", "P2P não arrancou: ${t.message}")
            }

            // Load any cached unsafe zones so the geofence works before the first fetch.
            com.lasvegas.secmess.model.UnsafePlacesRegistry.loadFromGeoJson(keyStoreHelper.getUnsafeGeoJson())

            // Relay canónico: migra qualquer custom_relay_url inválido (offline.invalid/mjfe/...).
            val storedUrl = keyStoreHelper.getRelayUrl()
            val canonicalUrl = RelayConfig.resolve(storedUrl)
            if (storedUrl != null && storedUrl != canonicalUrl) {
                android.util.Log.w("RelayService", "Relay inválido ($storedUrl); a migrar para $canonicalUrl")
                keyStoreHelper.setRelayUrl(canonicalUrl)
            }
            _currentRelayUrl.value = canonicalUrl

            while (isActive) {
                android.util.Log.d("RelayService", "Loop iteration - checking relay status")
                val currentRelay = _relayState.value
                val isConnected = currentRelay?.connectionStatus?.value == "Connected"
                android.util.Log.d("RelayService", "isConnected=$isConnected, relay=$currentRelay")

                val nowMs = System.currentTimeMillis()
                lastAliveAt = nowMs
                if (isConnected) {
                    lastConnectedAt = nowMs
                    reconnectAttempts = 0
                    // Republica o descritor a cada 5 min (se o primeiro envio falhou por
                    // o relay/rede ainda não estar pronto, isto corrige sem reiniciar).
                    if (nowMs - lastDescriptorAt > 300_000L) {
                        P2PManager.descriptorJson()?.let { d ->
                            lastDescriptorAt = nowMs
                            currentRelay?.publishDescriptor(d)
                            android.util.Log.i("OGP2P", "descriptor republicado")
                        }
                    }
                } else if (nowMs - maxOf(lastConnectedAt, lastResetAt, torStartedAt) > 75_000L) {
                    android.util.Log.w("RelayService", "Relay offline >75s; reset (attempt ${reconnectAttempts + 1})")
                    try { currentRelay?.disconnect() } catch (_: Exception) {}
                    _relayState.value = null
                    lastResetAt = nowMs
                    reconnectAttempts++
                    if (reconnectAttempts >= 2) {
                        reconnectAttempts = 0
                        torStartedAt = nowMs
                        android.util.Log.w("RelayService", "Restarting embedded Tor")
                        TorManager.restart(applicationContext)
                        // O onion service antigo morre com o Tor; recriar e republicar.
                        P2PManager.onTorRestart()
                    }
                    refreshChannel.trySend(Unit)
                    delay(1_000)
                    continue
                }

                // Discovery disabled - use hardcoded redyfined relay URL
                android.util.Log.d("RelayService", "Using configured relay URL (discovery disabled)")

                // 1b. Fetch server-published unsafe zones (GeoJSON) from the same gist.
                try {
                    val zonesUrl = "https://gist.githubusercontent.com/Kafila/82c96a7d9fda1c6fb3772cb6e8f3ec1e/raw/unsafe_zones.geojson?t=${System.currentTimeMillis()}"
                    val zc = TorManager.openConnection(zonesUrl)
                    zc.connectTimeout = 10000
                    if (zc.responseCode == 200) {
                        val gj = zc.inputStream.bufferedReader().use { it.readText() }.trim()
                        if (gj.isNotEmpty() && gj != keyStoreHelper.getUnsafeGeoJson()) {
                            keyStoreHelper.setUnsafeGeoJson(gj)
                            com.lasvegas.secmess.model.UnsafePlacesRegistry.loadFromGeoJson(gj)
                        }
                    }
                } catch (_: Exception) {
                    // zones fetch failed, keep cached
                }

                val relayUrl = _currentRelayUrl.value
                
                val connectionUrl = when {
                    relayUrl.startsWith("wss://") || relayUrl.startsWith("ws://") -> relayUrl
                    relayUrl.startsWith("https://") -> relayUrl.replace("https://", "wss://")
                    relayUrl.startsWith("http://") -> relayUrl.replace("http://", "ws://")
                    else -> "wss://$relayUrl"
                }

                if (_relayState.value == null || !isConnected) {
                    if (_relayState.value == null) {
                        // O Tor embebido já devia ter publicado a porta SOCKS.
                        if (connectionUrl.contains(".onion")) {
                            val port = TorManager.socksPort.value
                            if (port == null) {
                                android.util.Log.w("RelayService", "SOCKS ainda não pronto; a tentar ligar")
                            } else {
                                android.util.Log.d("RelayService", "SOCKS pronto em 127.0.0.1:$port")
                            }
                        }

                        android.util.Log.d("RelayService", "Creating new WebSocketRelay: $connectionUrl")
                        val newRelay = WebSocketRelay(serverUrl = connectionUrl, userId = myUserId)
                        _relayState.value = newRelay
                        android.util.Log.d("RelayService", "Calling relay.connect()")
                        newRelay.connect(this@RelayService)
                        android.util.Log.d("RelayService", "relay.connect() returned")
                    } else {
                        android.util.Log.d("RelayService", "Reconnecting existing relay")
                        _relayState.value?.reconnect()
                    }
                }

                // 1c. Self-hosted update check (over the relay tunnel; no public GitHub, no token).
                // Corre ao abrir a app (forceUpdateCheck) e periodicamente; não é one-shot.
                if (forceUpdateCheck || System.currentTimeMillis() >= nextUpdateCheckAt) {
                    forceUpdateCheck = false
                    try {
                        val base = connectionUrl.replace("wss://", "https://").replace("ws://", "http://")
                        val vc = TorManager.openConnection("$base/update/version?t=${System.currentTimeMillis()}")
                        vc.connectTimeout = 10000
                        if (vc.responseCode == 200) {
                            val remote = vc.inputStream.bufferedReader().use { it.readText() }.trim().toIntOrNull() ?: 0
                            val pInfo = packageManager.getPackageInfo(packageName, 0)
                            val local = if (android.os.Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode.toInt() else @Suppress("DEPRECATION") pInfo.versionCode
                            val now = System.currentTimeMillis()
                            if (remote > local) {
                                // Se o APK já está em cache, abrir já o instalador (mesmo
                                // que o download tenha sido feito em background).
                                val prompted = try {
                                    withContext(Dispatchers.Main) {
                                        com.lasvegas.secmess.network.UpdateManager.installCached(this@RelayService, remote)
                                    }
                                } catch (_: Throwable) { false }
                                if (!prompted) {
                                    // Cooldown por versão: se o utilizador recusou instalar,
                                    // não voltar a descarregar o mesmo APK a cada abertura.
                                    val up = getSharedPreferences("og_update", MODE_PRIVATE)
                                    val lastCode = up.getInt("last_code", 0)
                                    val lastAt = up.getLong("last_at", 0L)
                                    if (!com.lasvegas.secmess.network.UpdateManager.isDownloading() &&
                                        (remote != lastCode || now - lastAt > 6 * 60 * 60_000L)) {
                                        up.edit().putInt("last_code", remote).putLong("last_at", now).apply()
                                        withContext(Dispatchers.Main) {
                                            com.lasvegas.secmess.network.UpdateManager.startUpdate(this@RelayService, "$base/update/app.apk")
                                        }
                                    }
                                }
                                nextUpdateCheckAt = now + 6 * 60 * 60_000L
                            } else {
                                nextUpdateCheckAt = now + 30 * 60_000L
                            }
                        } else {
                            nextUpdateCheckAt = System.currentTimeMillis() + 10 * 60_000L
                        }
                    } catch (_: Exception) {
                        // Retry later instead of giving up until the next service restart.
                        nextUpdateCheckAt = System.currentTimeMillis() + 10 * 60_000L
                    }
                }
                
                withTimeoutOrNull(if (isConnected) 60000 else 10000) {
                    refreshChannel.receive()
                }
            }
        }
    }

    private fun showForegroundNotification() {
        val channelId = "relay_service_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(channelId, "Calendar Sync", NotificationManager.IMPORTANCE_MIN)
        channel.setSound(null, null)
        channel.enableVibration(false)
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Calendar Sync Active")
            .setContentText("Keeping your schedule up to date")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val fgsType = if (android.os.Build.VERSION.SDK_INT >= 34) {
            // Mensagens: não entra no limite de 6 h/dia do dataSync nem no bloqueio de boot.
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(1, notification, fgsType)
    }

    private fun scheduleRelayReset(reason: String) {
        scope.launch {
            val now = System.currentTimeMillis()
            if (now - lastResetAt < 5_000L) return@launch
            lastResetAt = now
            android.util.Log.w("RelayService", "Network change ($reason) -> reset relay")
            try { _relayState.value?.disconnect() } catch (_: Exception) {}
            _relayState.value = null
            refreshChannel.trySend(Unit)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try {
            networkCallback?.let {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
        try { _relayState.value?.disconnect() } catch (_: Exception) {}
        _relayState.value = null
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
