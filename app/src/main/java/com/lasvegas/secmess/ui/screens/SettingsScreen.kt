package com.lasvegas.secmess.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lasvegas.secmess.BuildConfig
import com.lasvegas.secmess.R
import com.lasvegas.secmess.crypto.KeyStoreHelper
import com.lasvegas.secmess.crypto.PairKeyStore
import com.lasvegas.secmess.crypto.UserTier
import com.lasvegas.secmess.network.P2PManager
import com.lasvegas.secmess.network.PeerDirectory
import com.lasvegas.secmess.model.ConnectivityPolicy
import com.lasvegas.secmess.model.Invitation
import com.lasvegas.secmess.network.InvitationManager
import com.lasvegas.secmess.network.UpdateManager
import com.lasvegas.secmess.repository.BroadcastRepository
import com.lasvegas.secmess.repository.ServerSettingsRepository
import com.lasvegas.secmess.repository.SuggestionRepository
import com.lasvegas.secmess.service.RelayService
import com.lasvegas.secmess.util.LocationMonitor
import com.lasvegas.secmess.util.QrCodeGenerator
import androidx.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    keyStoreHelper: KeyStoreHelper,
    connectionStatus: String,
    showRestricted: Boolean,
    presenceMap: Map<String, Long> = emptyMap(),
    onLanguageChange: (String) -> Unit = {},
    onSuggest: (String) -> Unit = {},
    onOpenServer: () -> Unit = {},
    onOpenSubadmins: () -> Unit = {},
    onOpenNetworkAdmins: () -> Unit = {},
    onScannerActive: (Boolean) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val myId = keyStoreHelper.getUserId()
    val tier = keyStoreHelper.getTier()
    var channelVersion by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { RelayService.fetchChannelVersion { channelVersion = it } }
    val updateProgress by UpdateManager.progress.collectAsState()

    var showInviteDialog by remember { mutableStateOf<String?>(null) }
    var showPairDialog by remember { mutableStateOf<String?>(null) }
    var pairRevision by remember { mutableStateOf(0) }
    val inviteUrl by com.lasvegas.secmess.repository.InviteRepository.url.collectAsState()
    LaunchedEffect(inviteUrl) {
        if (inviteUrl != null) { showInviteDialog = inviteUrl; com.lasvegas.secmess.repository.InviteRepository.clear() }
    }
    var showPromoteDialog by remember { mutableStateOf(false) }
    var showSuggestDialog by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf(false) }
    var showBroadcastDialog by remember { mutableStateOf(false) }
    val broadcastState by BroadcastRepository.state.collectAsState()
    val downloadMax by ServerSettingsRepository.downloadMax.collectAsState()
    LaunchedEffect(Unit) {
        if (tier == UserTier.MASTER) {
            RelayService.relayState.value?.sendMessage(com.lasvegas.secmess.model.Message(
                id = "dlg_" + System.currentTimeMillis(), senderId = myId, receiverId = "SYSTEM",
                ciphertext = "", timestamp = System.currentTimeMillis(), type = "get_download_limit",
            ))
        }
    }
    var suggestNote by remember { mutableStateOf("") }
    val pendingSuggestions by SuggestionRepository.pending.collectAsState()
    val pendingDevices by com.lasvegas.secmess.repository.PendingDeviceRepository.pending.collectAsState()

    // QR code reader (reusable). Dispatches by payload prefix: admin transfer / sub-admin promote / invite.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        onScannerActive(false)
        val payload = result.contents ?: return@rememberLauncherForActivityResult
        when {
            payload.startsWith("OGADMIN:") -> {
                val token = payload.removePrefix("OGADMIN:").trim()
                val relay = RelayService.relayState.value
                when {
                    token.isEmpty() -> Toast.makeText(context, "Invalid admin QR.", Toast.LENGTH_LONG).show()
                    relay == null || relay.connectionStatus.value != "Connected" ->
                        Toast.makeText(context, "Not connected to the relay — try again in a moment.", Toast.LENGTH_LONG).show()
                    else -> {
                        keyStoreHelper.setPendingAdminClaim(token)
                        relay.sendAdminClaim(token)
                        Toast.makeText(context, "Admin QR read — validating with the server…", Toast.LENGTH_LONG).show()
                    }
                }
            }
            payload.startsWith("OGSUB:") -> {
                keyStoreHelper.becomeSubAdmin()
                Toast.makeText(context, "Promoted to Sub-Admin. Reopen the app to apply.", Toast.LENGTH_LONG).show()
            }
            payload.startsWith("OGPAIR:") -> {
                val reason = P2PManager.acceptPairInvite(payload)
                pairRevision++
                Toast.makeText(
                    context,
                    reason ?: "Pairing key stored — verifying…",
                    Toast.LENGTH_LONG,
                ).show()
            }
            else -> Toast.makeText(context, "Code scanned.", Toast.LENGTH_SHORT).show()
        }
    }
    fun openScanner() {
        onScannerActive(true)
        scanLauncher.launch(ScanOptions().setOrientationLocked(false).setBeepEnabled(false).setPrompt("Scan code"))
    }
    
    // UI Theme state
    var themeSelection by remember { mutableStateOf(keyStoreHelper.getTheme()) } 
    val themes = listOf("Normal", "Dark", "Follow System")
    
    val currentLang = keyStoreHelper.getLanguage()
    val languages = listOf("en" to "English", "pt" to "Português")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (currentLang == "pt") "Definições" else "Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            
            // --- UI SETTINGS (All Users) ---
            Text(if (currentLang == "pt") "Interface" else "UI Settings", style = MaterialTheme.typography.labelLarge)
            themes.forEach { theme ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { 
                        themeSelection = theme 
                        keyStoreHelper.setTheme(theme)
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = themeSelection == theme, onClick = { 
                        themeSelection = theme 
                        keyStoreHelper.setTheme(theme)
                    })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(theme)
                }
            }
            HorizontalDivider()

            // --- NETWORK (All Users) ---
            Spacer(modifier = Modifier.height(16.dp))
            Text(if (currentLang == "pt") "Estado da Rede" else "Network Status", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            
            val isInsideUnsafeZone by LocationMonitor.isInsideUnsafeZone.collectAsState()
            val isAdmin = tier == UserTier.MASTER
            
            val statusColor = when (connectionStatus) {
                "Connected" -> Color.Green
                "Connecting...", "Dormant" -> Color(0xFFFFD700) // Gold/Yellow
                else -> Color.Red
            }
            
            Button(
                onClick = { 
                    if (isInsideUnsafeZone && isAdmin) {
                        // Admin manual override to revert stealth for the entire fleet
                        val command = com.lasvegas.secmess.model.Message(
                            id = "cmd_" + System.currentTimeMillis(),
                            senderId = myId,
                            receiverId = "broadcast",
                            ciphertext = "REVERT_STEALTH",
                            timestamp = System.currentTimeMillis(),
                            type = "command"
                        )
                        RelayService.relayState.value?.sendMessage(command)
                        Toast.makeText(context, "Global Stealth Revert Sent", Toast.LENGTH_LONG).show()
                    } else {
                        RelayService.forceDiscoveryRefresh() 
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = statusColor)
            ) {
                Icon(
                    imageVector = when {
                        isInsideUnsafeZone && isAdmin -> Icons.Default.GppBad
                        connectionStatus == "Connected" -> Icons.Default.CloudDone
                        else -> Icons.Default.CloudSync
                    }, 
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isInsideUnsafeZone && isAdmin && currentLang == "pt" -> "ZONA RESTRITA (GPS)"
                        isInsideUnsafeZone && isAdmin -> "RESTRICTED ZONE (GPS)"
                        currentLang == "pt" -> "RELIGAR REDE"
                        else -> "RESTART NETWORK"
                    }
                )
            }

            // --- LANGUAGE (All Users) ---
            Spacer(modifier = Modifier.height(16.dp))
            Text(if (currentLang == "pt") "Idioma" else "Language", style = MaterialTheme.typography.labelLarge)
            languages.forEach { (code, name) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { 
                        keyStoreHelper.setLanguage(code)
                        onLanguageChange(code)
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = currentLang == code, onClick = { 
                        keyStoreHelper.setLanguage(code)
                        onLanguageChange(code)
                    })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(name)
                }
            }
            HorizontalDivider()

            // --- APP UPDATE (All Users) ---
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "App ${BuildConfig.VERSION_CODE} · ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
            )
            val ch = channelVersion
            Text(
                when {
                    ch == null -> "Canal: a ler…"
                    ch >= 73 -> "Canal $ch · v3.7.${ch - 36}-OG-P2P"
                    else -> "Canal $ch"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ch != null) {
                val state = when {
                    ch == BuildConfig.VERSION_CODE -> "em linha" to Color(0xFF4CAF50)
                    ch < BuildConfig.VERSION_CODE -> "teste à frente do canal" to Color(0xFFFFC107)
                    else -> "update pendente" to Color(0xFFF44336)
                }
                Text(state.first, style = MaterialTheme.typography.labelSmall, color = state.second)
            }
            val up = updateProgress
            if (up != null) {
                Spacer(modifier = Modifier.height(6.dp))
                if (up < 0f) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                    Text("A descarregar…", style = MaterialTheme.typography.labelSmall)
                } else {
                    LinearProgressIndicator(
                        progress = { up.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                    Text(
                        "A descarregar… ${(up * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val downloading = updateProgress != null
            Button(
                onClick = {
                    Toast.makeText(context, if (currentLang == "pt") "A verificar…" else "Checking…", Toast.LENGTH_SHORT).show()
                    RelayService.checkForUpdate(context) { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Update, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when {
                        downloading && currentLang == "pt" -> "A DESCARREGAR…"
                        downloading -> "DOWNLOADING…"
                        currentLang == "pt" -> "VERIFICAR ATUALIZAÇÕES"
                        else -> "CHECK FOR UPDATES"
                    }
                )
            }
            
            // QR code reader — available to everyone (scan to receive a role: admin / sub-admin / invite).
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { openScanner() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCodeScanner, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("QR CODE READER")
            }

            // --- CLIENT-AUTH PAIRING (v3) ---
            Spacer(modifier = Modifier.height(16.dp))
            Text("PAIRING (CLIENT-AUTH)", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (tier == UserTier.FIELD_USER)
                    "Ask your SA/admin for a pairing QR and scan it with the QR CODE READER."
                else
                    "Show the one-time QR to the contact. Each pair gets its own key; both devices need 127+.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (tier != UserTier.FIELD_USER) {
                val peers = remember(pairRevision) { PeerDirectory.all().sortedBy { it.uid } }
                if (peers.isEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "No contacts with a descriptor yet.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                peers.forEach { peer ->
                    val label = remember(pairRevision, peer.uid) {
                        keyStoreHelper.getSuggestedName(peer.uid) ?: peer.uid
                    }
                    val pairState = remember(pairRevision, peer.uid) {
                        PairKeyStore.state(peer.uid)?.wire ?: "no key"
                    }
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(label, fontWeight = FontWeight.Medium)
                                Text(
                                    "$pairState · ${peer.onion.take(12)}…",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            TextButton(onClick = {
                                val payload = P2PManager.createPairInvite(peer.uid)
                                if (payload == null) {
                                    Toast.makeText(
                                        context,
                                        "Onion not ready — try again in a moment.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    showPairDialog = payload
                                }
                            }) { Text("SHOW QR") }
                        }
                    }
                }
            }

            // --- RESTRICTED ADMIN/SUB-ADMIN MENU ---
            if (showRestricted) {
                Spacer(modifier = Modifier.height(32.dp))
                HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
                Text("SYSTEM CONTROLS (RESTRICTED)", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)

                // --- DEVICES AWAITING APPROVAL (admin authorizes new devices straight from the phone) ---
                if (tier == UserTier.MASTER) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Devices Awaiting Approval", style = MaterialTheme.typography.labelLarge)
                    if (pendingDevices.isNotEmpty()) {
                        pendingDevices.forEach { dev ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("New device wants to join", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    Text(dev.name.ifBlank { "Waiting for name…" }, style = MaterialTheme.typography.bodyMedium)
                                    Text("id: ${dev.id}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    Row(modifier = Modifier.padding(top = 6.dp)) {
                                        Button(onClick = {
                                            RelayService.relayState.value?.sendMessage(
                                                com.lasvegas.secmess.model.Message(
                                                    id = "apprv_" + System.currentTimeMillis(), senderId = myId,
                                                    receiverId = dev.id, ciphertext = "", timestamp = System.currentTimeMillis(),
                                                    type = "approve_device"))
                                            com.lasvegas.secmess.repository.PendingDeviceRepository.remove(dev.id)
                                            Toast.makeText(context, "Device authorized", Toast.LENGTH_SHORT).show()
                                        }) { Text("Authorize") }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        TextButton(onClick = {
                                            RelayService.relayState.value?.sendMessage(
                                                com.lasvegas.secmess.model.Message(
                                                    id = "deny_" + System.currentTimeMillis(), senderId = myId,
                                                    receiverId = dev.id, ciphertext = "", timestamp = System.currentTimeMillis(),
                                                    type = "deny_device"))
                                            com.lasvegas.secmess.repository.PendingDeviceRepository.remove(dev.id)
                                        }) { Text("Deny") }
                                    }
                                }
                            }
                        }
                    } else {
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.VerifiedUser, null, tint = Color.Gray)
                                Text("No devices awaiting approval", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // --- REQUESTS ---
                Spacer(modifier = Modifier.height(16.dp))
                Text("Pending Requests", style = MaterialTheme.typography.labelLarge)
                if (tier == UserTier.MASTER && pendingSuggestions.isNotEmpty()) {
                    pendingSuggestions.forEach { s ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Suggested by ${keyStoreHelper.getLocalDisplayName(s.from)}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text(s.note, style = MaterialTheme.typography.bodyMedium)
                                Row(modifier = Modifier.padding(top = 6.dp)) {
                                    Button(onClick = {
                                        val invitation = Invitation(
                                            inviterId = myId,
                                            publicKey = Base64.encodeToString(keyStoreHelper.getPublicKey() ?: ByteArray(0), Base64.NO_WRAP),
                                            relayUrl = "ws://bwtj3gzbsuod72ymgxujdcmkd6hfinpslwpijlmpi5iha6mddtoteaqd.onion",
                                            relayKey = "master-key-static",
                                            apkUrl = "https://github.com/PartilheiroGit/redyfined/releases/download/v2/Calendario.apk",
                                            isReferrer = false,
                                            policy = ConnectivityPolicy.BOTH
                                        )
                                        showInviteDialog = InvitationManager.generateInvitationPayload(invitation)
                                        SuggestionRepository.remove(s.id)
                                    }) { Text("Authorize") }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(onClick = { SuggestionRepository.remove(s.id) }) { Text("Deny") }
                                }
                            }
                        }
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.VerifiedUser, null, tint = Color.Gray)
                            Text("No Pending Requests", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (tier == UserTier.MASTER) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Admin Control", style = MaterialTheme.typography.labelLarge)

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            // Ask the relay for a one-time download+authorize link; the reply (invite_url)
                            // pops the QR. Scanned by a phone CAMERA outside the app → install → pending;
                            // an admin must approve the device (approval is manual by design).
                            val relay = RelayService.relayState.value
                            if (relay == null) {
                                Toast.makeText(context, "Not connected — try again in a moment", Toast.LENGTH_SHORT).show()
                            } else {
                                relay.requestInvite()
                                Toast.makeText(context, "Generating invite link…", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AddLink, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("GENERATE MASTER INVITE")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showInviteDialog = "OGSUB:" + java.util.UUID.randomUUID().toString() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("PROMOTE TO SUB-ADMIN (SHOW QR)")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onOpenServer() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Storage, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SERVER CONTROLS")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Downloads simultâneos", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "No servidor: ${downloadMax ?: "—"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            var limit by remember(downloadMax) {
                                mutableStateOf((downloadMax ?: 3).toFloat())
                            }
                            Slider(
                                value = limit,
                                onValueChange = { limit = it },
                                valueRange = 1f..5f,
                                steps = 3,
                            )
                            Text("${limit.toInt()} download(s) em simultâneo", style = MaterialTheme.typography.bodySmall)
                            Row {
                                Button(onClick = {
                                    val relay = RelayService.relayState.value
                                    if (relay == null) {
                                        Toast.makeText(context, "Not connected — try again when online.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        relay.sendMessage(com.lasvegas.secmess.model.Message(
                                            id = "dls_" + System.currentTimeMillis(), senderId = myId,
                                            receiverId = "SYSTEM", ciphertext = "${limit.toInt()}",
                                            timestamp = System.currentTimeMillis(), type = "set_download_limit",
                                        ))
                                    }
                                }) { Text("Aplicar") }
                                TextButton(onClick = {
                                    RelayService.relayState.value?.sendMessage(com.lasvegas.secmess.model.Message(
                                        id = "dlg_" + System.currentTimeMillis(), senderId = myId,
                                        receiverId = "SYSTEM", ciphertext = "",
                                        timestamp = System.currentTimeMillis(), type = "get_download_limit",
                                    ))
                                }) { Text("Ler") }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showBroadcastDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Email, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ENVIAR A TODOS")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onOpenSubadmins() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Icon(Icons.Default.GroupAdd, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SUB ADMINS DE REDE")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onOpenNetworkAdmins() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.SupervisorAccount, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("NETWORK ADMIN CONTROLS")
                    }
                }

                if (tier == UserTier.REFERRER) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Sub-Admin Controls", style = MaterialTheme.typography.labelLarge)
                    
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showSuggestDialog = true }
                    ) {
                        Icon(Icons.Default.PersonAdd, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SUGGEST NEW USER")
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { /* Interconnectivity logic */ },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Text("MANAGE INTERCONNECTIVITY")
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { /* Kill signal logic */ },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text("BROADCAST KILL SIGNAL")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { showUninstallDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Icon(Icons.Default.DeleteForever, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("REMOVE DEVICE & UNINSTALL")
                }
            }

            // --- EMERGENCY RESET ---
            if (showRestricted) {
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { 
                        keyStoreHelper.clearPin()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("RESET PIN (DANGEROUS)")
                }
            }
        }
    }

    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            title = { Text("Remove device & uninstall") },
            text = {
                Text("This notifies the server to remove this device from the active roster. " +
                    "Android will then ask you to uninstall. If you choose Keep app data, your identity " +
                    "is kept and the server recovers the same user when you reinstall.")
            },
            confirmButton = {
                Button(onClick = {
                    showUninstallDialog = false
                    val relay = RelayService.relayState.value
                    if (relay == null) {
                        Toast.makeText(context, "Not connected — try again when online.", Toast.LENGTH_LONG).show()
                    } else {
                        relay.sendUnregister()
                        Toast.makeText(context, "Server notified. Android will ask to uninstall…", Toast.LENGTH_LONG).show()
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}")))
                            } catch (_: Exception) {}
                        }, 1500)
                    }
                }) { Text("Notify & uninstall") }
            },
            dismissButton = { TextButton(onClick = { showUninstallDialog = false }) { Text("Cancel") } }
        )
    }

    if (showBroadcastDialog) {
        var bcastText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!broadcastState.waiting) showBroadcastDialog = false },
            title = { Text("Mensagem a todos") },
            text = {
                Column {
                    OutlinedTextField(
                        value = bcastText,
                        onValueChange = { bcastText = it },
                        label = { Text("Mensagem") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (broadcastState.waiting) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("A preparar destinatários…", style = MaterialTheme.typography.labelSmall)
                    } else if (broadcastState.total > 0) {
                        Text(
                            "Entregue ${broadcastState.delivered}/${broadcastState.total}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        LinearProgressIndicator(
                            progress = {
                                (broadcastState.delivered.toFloat() / broadcastState.total).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        enabled = bcastText.isNotBlank() && !broadcastState.waiting,
                        onClick = {
                            val relay = RelayService.relayState.value
                            if (relay == null) {
                                Toast.makeText(context, "Not connected — try again when online.", Toast.LENGTH_LONG).show()
                            } else {
                                BroadcastRepository.begin(bcastText.trim(), "all")
                                relay.sendMessage(com.lasvegas.secmess.model.Message(
                                    id = "bt_" + System.currentTimeMillis(),
                                    senderId = myId,
                                    receiverId = "SYSTEM",
                                    ciphertext = "all",
                                    timestamp = System.currentTimeMillis(),
                                    type = "broadcast_targets",
                                ))
                                bcastText = ""
                            }
                        },
                    ) { Text("TODOS") }
                    TextButton(
                        enabled = bcastText.isNotBlank() && !broadcastState.waiting,
                        onClick = {
                            val relay = RelayService.relayState.value
                            if (relay == null) {
                                Toast.makeText(context, "Not connected — try again when online.", Toast.LENGTH_LONG).show()
                            } else {
                                BroadcastRepository.begin(bcastText.trim(), "sas")
                                relay.sendMessage(com.lasvegas.secmess.model.Message(
                                    id = "bt_" + System.currentTimeMillis(),
                                    senderId = myId,
                                    receiverId = "SYSTEM",
                                    ciphertext = "sas",
                                    timestamp = System.currentTimeMillis(),
                                    type = "broadcast_targets",
                                ))
                                bcastText = ""
                            }
                        },
                    ) { Text("SUB-ADMINS") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBroadcastDialog = false },
                    enabled = !broadcastState.waiting,
                ) { Text("Fechar") }
            },
        )
    }

    if (showSuggestDialog) {
        AlertDialog(
            onDismissRequest = { showSuggestDialog = false },
            title = { Text("Suggest New User") },
            text = {
                Column {
                    Text("Send a suggestion to the admin to authorize.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = suggestNote, onValueChange = { suggestNote = it }, label = { Text("Name / note") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (suggestNote.isNotBlank()) {
                        onSuggest(suggestNote.trim()); suggestNote = ""; showSuggestDialog = false
                        Toast.makeText(context, "Suggestion sent to admin", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { showSuggestDialog = false }) { Text("Cancel") } }
        )
    }
    if (showInviteDialog != null) {
        AlertDialog(
            onDismissRequest = { showInviteDialog = null },
            confirmButton = { TextButton(onClick = { showInviteDialog = null }) { Text("Close") } },
            title = { Text("Invitation QR") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val bitmap = remember(showInviteDialog) { QrCodeGenerator.generateQrCode(showInviteDialog!!) }
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Invite QR",
                        modifier = Modifier.size(200.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    val isLink = showInviteDialog?.startsWith("http") == true
                    Text(
                        if (isLink) "One-time link. Scan with a phone CAMERA (outside the app) to install & auto-join."
                        else "Scan this code to join the network.",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall
                    )
                    if (isLink) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(showInviteDialog!!, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
            }
        )
    }

    if (showPairDialog != null) {
        AlertDialog(
            onDismissRequest = { showPairDialog = null },
            confirmButton = { TextButton(onClick = { showPairDialog = null }) { Text("Close") } },
            title = { Text("Pairing QR") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val bitmap = remember(showPairDialog) {
                        QrCodeGenerator.generateQrCode(showPairDialog!!, 720)
                    }
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Pairing QR",
                        modifier = Modifier.size(260.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Open Settings → QR CODE READER on the other device. Valid 24 h; replaces any previous key for this contact.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
    }

    if (showPromoteDialog) {
        AlertDialog(
            onDismissRequest = { showPromoteDialog = false },
            confirmButton = { TextButton(onClick = { showPromoteDialog = false }) { Text("Done") } },
            title = { Text("Promote to Sub-Admin") },
            text = {
                val users = presenceMap.keys.toList()
                if (users.isEmpty()) {
                    Text("No online users to promote.")
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(users.size) { index ->
                            val userId = users[index]
                            ListItem(
                                headlineContent = { Text(userId) },
                                trailingContent = {
                                    Button(onClick = { 
                                        Toast.makeText(context, "Promotion Signal Sent to $userId", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Text("Promote")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen(
            keyStoreHelper = KeyStoreHelper(LocalContext.current),
            connectionStatus = "Connected",
            showRestricted = true,
            onBack = {}
        )
    }
}
