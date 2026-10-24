package com.lasvegas.secmess

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lasvegas.secmess.crypto.CryptoManager
import com.lasvegas.secmess.crypto.KeyStoreHelper
import com.lasvegas.secmess.model.Message
import com.lasvegas.secmess.repository.AgendaRepository
import com.lasvegas.secmess.repository.MessageRepository
import com.lasvegas.secmess.service.RelayService
import com.lasvegas.secmess.ui.screens.AdminHubScreen
import com.lasvegas.secmess.ui.screens.CalendarScreen
import com.lasvegas.secmess.ui.screens.EnrollmentInstallScreen
import com.lasvegas.secmess.ui.screens.SecretChatScreen
import com.lasvegas.secmess.ui.screens.SettingsScreen
import com.lasvegas.secmess.ui.screens.ServerDashboardScreen
import com.lasvegas.secmess.ui.screens.SubAdminManagerScreen
import com.lasvegas.secmess.ui.theme.SecMessTheme
import com.lasvegas.secmess.util.LocationMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    /** Data do evento-disfarce da notificação que abriu a app. */
    private var notificationDate by mutableStateOf<String?>(null)

    private var keyStoreHelperState = mutableStateOf<KeyStoreHelper?>(null)
    private var myUserIdState = mutableStateOf("pending")

    private val multiplePermissionsRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        
        // Start services
        RelayService.start(this)
        com.lasvegas.secmess.util.LocationMonitor.startMonitoring(this)
        
        if (!fineLocationGranted || !cameraGranted) {
            Toast.makeText(this, "Permissions partial. Some features disabled.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationDate = intent.getStringExtra(com.lasvegas.secmess.util.NotificationHelper.EXTRA_DATE)
        // Lock to portrait so a rotation can't recreate the activity and bounce to the calendar.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // App is open -> status ONLINE (even with screen off) until the activity is destroyed.
        com.lasvegas.secmess.util.AppState.appOpen = true
        com.lasvegas.secmess.repository.OutboundQueue.init(applicationContext)
        com.lasvegas.secmess.service.KeepAliveJobService.schedule(applicationContext)
        requestBatteryExemption()

        setContent {
            var fatalError by remember { mutableStateOf<String?>(null) }
            var isBooting by remember { mutableStateOf(true) }
            
            if (fatalError != null) {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red)
                    .padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("RESCUE UI - CRITICAL ERROR:\n\n$fatalError", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { recreate() }) { Text("Tentar novamente") }
                    }
                }
            } else if (isBooting) {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black), contentAlignment = Alignment.Center) {
                    Text("Initializing O.G. Systems...", color = Color.Gray)
                }
                LaunchedEffect(Unit) {
                    try {
                        delay(1000)
                        withContext(Dispatchers.IO) {
                            val helper = KeyStoreHelper(this@MainActivity)
                            if (helper.getPublicKey() == null) {
                                val keyPair = CryptoManager.generateX25519KeyPair()
                                helper.storePrivateKey(keyPair.private)
                                helper.storePublicKey(keyPair.public.encoded)
                            }
                            KeyStoreHelper.adminRevokedState.value = helper.isAdminRevoked()
                            val userId = helper.getUserId()
                            withContext(Dispatchers.Main) {
                                keyStoreHelperState.value = helper
                                myUserIdState.value = userId

                                // Rule "The Opposite": Always request on boot to be operationally ready
                                requestAllPermissions()

                                isBooting = false
                            }
                        }
                    } catch (e: CancellationException) {
                        // Activity destroyed/recreated durante o boot (ex.: update por adb
                        // com o ecrã bloqueado). Não é um erro fatal: não pintar o rescue UI.
                    } catch (e: Exception) {
                        if (currentCoroutineContext().isActive) {
                            fatalError = "Init failed: ${e.message}"
                        }
                    }
                }
            } else {
                val helper = keyStoreHelperState.value!!
                val myUserId = myUserIdState.value
                val revoked by KeyStoreHelper.adminRevokedState.collectAsState()
                val isAdminDevice by remember(myUserId, revoked) { mutableStateOf(myUserId == "admin_001" && !revoked) }
                
                val appTheme = helper.getTheme()
                val isDark = when (appTheme) {
                    "Dark" -> true
                    "Normal" -> false
                    else -> androidx.compose.foundation.isSystemInDarkTheme()
                }

                // Rule: Always start in calendar unless explicitly unlocked this session AND not in stealth
                var currentScreen by remember {
                    mutableStateOf("calendar")
                }
                LaunchedEffect(notificationDate) {
                    if (notificationDate != null) currentScreen = "calendar"
                }
                var scannerActive by remember { mutableStateOf(false) }
                var showEnrollmentInstall by remember { mutableStateOf(false) }
                var canSeeRestrictedSettings by remember { mutableStateOf(false) }
                keyStoreHelperState.value // Trigger recompose on helper updates if any

                val ctx = this@MainActivity
                LaunchedEffect(currentScreen) {
                    val inMsg = currentScreen == "user_list" || currentScreen == "admin_hub" || currentScreen == "chat"
                    com.lasvegas.secmess.util.NotificationHelper.inMessenger = inMsg
                    if (inMsg) com.lasvegas.secmess.util.NotificationHelper.seen(ctx)
                }
                DisposableEffect(Unit) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && !scannerActive) currentScreen = "calendar"
                    }
                    ctx.lifecycle.addObserver(obs)
                    onDispose { ctx.lifecycle.removeObserver(obs) }
                }

                Box {
                    SecMessTheme(darkTheme = isDark) {
                        var activeChatUserId by remember { mutableStateOf<String?>(null) }
                        var hasPin by remember { mutableStateOf(helper.hasPin()) }

                        // Robust State Collection
                        val relay by RelayService.relayState.collectAsState()

                        val presenceMap = remember(relay) {
                            relay?.presenceMap ?: MutableStateFlow(emptyMap())
                        }.collectAsState().value

                        val userStatusMap = remember(relay) {
                            relay?.userStatusMap ?: MutableStateFlow(emptyMap())
                        }.collectAsState().value

                        val connectionStatus = remember(relay) {
                            relay?.connectionStatus ?: MutableStateFlow("Disconnected")
                        }.collectAsState().value

                        val isInsideUnsafeZoneFlow by LocationMonitor.isInsideUnsafeZone.collectAsState()
                        val isInsideUnsafeZone =
                            (isInsideUnsafeZoneFlow || helper.isStealthActive()) && !isAdminDevice

                        LaunchedEffect(revoked) {
                            if (revoked) {
                                showEnrollmentInstall = false
                                currentScreen = "calendar"
                                Toast.makeText(this@MainActivity, "Admin transferred — this device is locked to the calendar.", Toast.LENGTH_LONG).show()
                            }
                        }

                        LaunchedEffect(isInsideUnsafeZone, currentScreen) {
                            if (isInsideUnsafeZone && currentScreen != "calendar") {
                                currentScreen = "calendar"
                                Toast.makeText(
                                    this@MainActivity,
                                    "Security Protocol: Location Restricted",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        LaunchedEffect(Unit) {
                            withContext(Dispatchers.IO) {
                                AgendaRepository.init(this@MainActivity)
                            }
                        }

                        if (showEnrollmentInstall) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                EnrollmentInstallScreen(
                                    onInstallStarted = { },
                                    onSkip = {
                                        showEnrollmentInstall = false
                                        currentScreen = if (BuildConfig.CALENDAR_ONLY) {
                                            "calendar"
                                        } else if (isAdminDevice) {
                                            "admin_hub"
                                        } else {
                                            "user_list"
                                        }
                                    }
                                )
                            }
                        } else {
                            when (currentScreen) {
                            "calendar" -> {
                                CalendarScreen(
                                    hasPin = hasPin,
                                    keyStoreHelper = helper,
                                    isRevoked = revoked,
                                    initialDate = notificationDate,
                                    onPinSet = { pin ->
                                        helper.storePin(pin)
                                        hasPin = true
                                        if (BuildConfig.CALENDAR_ONLY) {
                                            showEnrollmentInstall = true
                                        } else {
                                            showEnrollmentInstall = false
                                            currentScreen = if (isAdminDevice) "admin_hub" else "user_list"
                                        }
                                    },
                                    onPinVerify = { pin ->
                                        val valid = helper.verifyPin(pin)
                                        if (valid) {
                                            helper.setInitiallyUnlocked(true)
                                            if (BuildConfig.CALENDAR_ONLY) {
                                                showEnrollmentInstall = true
                                            } else {
                                                currentScreen = if (isAdminDevice) "admin_hub" else "user_list"
                                            }
                                        }
                                        valid
                                    },
                                    onUnlockSuccess = {
                                        if (!BuildConfig.CALENDAR_ONLY) {
                                            currentScreen = if (isAdminDevice) "admin_hub" else "user_list"
                                        }
                                    },
                                    isInsideUnsafeZone = isInsideUnsafeZone
                                )
                            }

                            "admin_hub", "user_list" -> {
                                var clickCount by remember { mutableIntStateOf(0) }
                                var showNickname by remember { mutableStateOf(!helper.hasMyNickname()) }
                                if (showNickname) {
                                    var nick by remember { mutableStateOf("") }
                                    AlertDialog(
                                        onDismissRequest = { },
                                        title = { Text("Choose your name") },
                                        text = {
                                            Column {
                                                Text("Pick a nickname others will see. Each contact can rename you privately on their own phone.")
                                                Spacer(Modifier.height(8.dp))
                                                OutlinedTextField(
                                                    value = nick,
                                                    onValueChange = { nick = it.take(24) },
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        },
                                        confirmButton = {
                                            Button(
                                                onClick = { if (nick.isNotBlank()) { helper.setMyNickname(nick.trim()); showNickname = false } },
                                                enabled = nick.isNotBlank()
                                            ) { Text("Save") }
                                        }
                                    )
                                }
                                AdminHubScreen(
                                    currentUserId = myUserId,
                                    presenceMap = presenceMap,
                                    connectionStatus = connectionStatus,
                                    keyStoreHelper = helper,
                                    onSelectUser = {
                                        activeChatUserId = it; currentScreen = "chat"
                                    },
                                    onBack = { currentScreen = "calendar" },
                                    onOpenSettings = { currentScreen = "settings" },
                                    userStatusMap = userStatusMap,
                                    onBannerClick = {
                                        clickCount++
                                        if (clickCount >= 3) {
                                            canSeeRestrictedSettings = true
                                            Toast.makeText(
                                                this@MainActivity,
                                                "System Access Primed",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                            }

                            "chat" -> SecretChatScreen(
                                currentUserId = myUserId,
                                targetUserId = activeChatUserId ?: "admin_001",
                                presenceMap = presenceMap,
                                keyStoreHelper = helper,
                                isInsideUnsafeZone = isInsideUnsafeZone,
                                onSendMessage = { text ->
                                    // Chave local do device (não está no APK).
                                    val encrypted = CryptoManager.encrypt(
                                        text.toByteArray(), helper.getOrCreateLocalKey())
                                    val msg = Message(
                                        id = UUID.randomUUID().toString(),
                                        senderId = myUserId,
                                        receiverId = activeChatUserId ?: "admin_001",
                                        ciphertext = encrypted,
                                        timestamp = com.lasvegas.secmess.repository.MessageClock.next()
                                    )
                                    MessageRepository.addMessage(msg)
                                    // Don't transmit yet — hold on this phone until the recipient
                                    // is confirmed connected. RelayService flushes the queue.
                                    com.lasvegas.secmess.repository.OutboundQueue.enqueue(msg)
                                },
                                onReadReceipt = { msgId, toUser ->
                                    relay?.sendMessage(Message(id = "rr_" + UUID.randomUUID(), senderId = myUserId, receiverId = toUser, ciphertext = msgId, timestamp = System.currentTimeMillis(), type = "read_receipt"))
                                },
                                onBack = {
                                    currentScreen = if (isAdminDevice) "admin_hub" else "user_list"
                                }
                            )

                            "settings" -> {
                                SettingsScreen(
                                    keyStoreHelper = helper,
                                    connectionStatus = connectionStatus,
                                    showRestricted = canSeeRestrictedSettings,
                                    presenceMap = presenceMap,
                                    onLanguageChange = { /* KeyStoreHelper already updated, state inside Settings is local */ },
                                    onSuggest = { note ->
                                        relay?.sendMessage(Message(id = "sg_" + UUID.randomUUID(), senderId = myUserId, receiverId = "admin_001", ciphertext = note, timestamp = System.currentTimeMillis(), type = "suggestion"))
                                    },
                                    onOpenServer = { currentScreen = "server_dashboard" },
                                    onOpenSubadmins = { currentScreen = "sub_admin_manager" },
                                    onOpenNetworkAdmins = { currentScreen = "network_admin_screen" },
                                    onScannerActive = { scannerActive = it },
                                    onBack = {
                                        canSeeRestrictedSettings = false // Reset on exit
                                        currentScreen =
                                            if (isAdminDevice) "admin_hub" else "user_list"
                                    },
                                )
                            }

                            "server_dashboard" -> {
                                ServerDashboardScreen(
                                    keyStoreHelper = helper,
                                    onBack = { currentScreen = "settings" }
                                )
                            }

                            "sub_admin_manager" -> {
                                SubAdminManagerScreen(
                                    keyStoreHelper = helper,
                                    onBack = { currentScreen = "settings" }
                                )
                            }

                            "network_admin_screen" -> {
                                AdminHubScreen(
                                    currentUserId = myUserId,
                                    presenceMap = presenceMap,
                                    connectionStatus = connectionStatus,
                                    keyStoreHelper = helper,
                                    onSelectUser = {
                                        activeChatUserId = it; currentScreen = "chat"
                                    },
                                    onBack = { currentScreen = "settings" },
                                    userStatusMap = userStatusMap,
                                    onOpenSettings = { currentScreen = "settings" }
                                )
                            }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationDate = intent.getStringExtra(com.lasvegas.secmess.util.NotificationHelper.EXTRA_DATE)
    }

    override fun onResume() {
        super.onResume()
        // Auto-update: verificar sempre que a app vem à frente (e no regresso do instalador).
        RelayService.requestUpdateCheck()
        // Localização só com a app aberta; o serviço de mensagens não usa GPS.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            com.lasvegas.secmess.util.LocationMonitor.startMonitoring(this)
        }
    }

    override fun onPause() {
        super.onPause()
        com.lasvegas.secmess.util.LocationMonitor.stopMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        // App closed (swiped away) -> status DORMANT; the background service keeps receiving.
        com.lasvegas.secmess.util.AppState.appOpen = false
    }

    /** Uma vez: pedir para o Android não otimizar a bateria (evita o congelamento em Doze). */
    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            val prefs = getSharedPreferences("og_keepalive", MODE_PRIVATE)
            if (prefs.getBoolean("asked", false)) return
            prefs.edit().putBoolean("asked", true).apply()
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName"),
            ))
        } catch (_: Exception) {
            // sem suporte/OEM: fica o job de 15 min como rede de segurança
        }
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        multiplePermissionsRequest.launch(perms.toTypedArray())
    }
}
