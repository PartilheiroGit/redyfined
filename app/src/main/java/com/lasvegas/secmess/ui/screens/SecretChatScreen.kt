package com.lasvegas.secmess.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lasvegas.secmess.crypto.CryptoManager
import com.lasvegas.secmess.model.Message
import com.lasvegas.secmess.repository.MessageRepository
import com.lasvegas.secmess.util.LocationMonitor
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretChatScreen(
    currentUserId: String,
    targetUserId: String,
    presenceMap: Map<String, Long>,
    keyStoreHelper: com.lasvegas.secmess.crypto.KeyStoreHelper,
    onSendMessage: (String) -> Unit,
    onReadReceipt: (String, String) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    isInsideHub: Boolean = false,
    isInsideUnsafeZone: Boolean = false,
    relay: com.lasvegas.secmess.network.WebSocketRelay? = null,
) {
    val messages by MessageRepository.messages.collectAsState()
    var text by remember { mutableStateOf("") }
    val displayTargetName = keyStoreHelper.getLocalDisplayName(targetUserId)
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // Send ping when user selects this recipient to mark device as ready
    LaunchedEffect(targetUserId) {
        relay?.sendPing(targetUserId)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            LocationMonitor.startMonitoring(context)
            Toast.makeText(context, "Location permission granted. Try again.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Location permission is required to send location.", Toast.LENGTH_SHORT).show()
        }
    }

    val lastSeen = presenceMap[targetUserId] ?: 0L
    val isOnline = (System.currentTimeMillis() - lastSeen) < 30000

    // Send a read receipt once per incoming message viewed -> sender sees ✓✓ (seen).
    // A partir daqui contam os 5 min até desaparecer (antes: 8 h desde a entrega).
    val receipted = remember { mutableSetOf<String>() }
    LaunchedEffect(messages) {
        messages.filter { it.senderId == targetUserId && it.receiverId == currentUserId && it.id !in receipted }
            .forEach {
                onReadReceipt(it.id, targetUserId)
                MessageRepository.markAsRead(it.id)
                receipted.add(it.id)
            }
    }

    val currentLocale = LocalConfiguration.current.locales[0]
    val localKey = keyStoreHelper.getOrCreateLocalKey()

    /**
     * Duplo toque: o emissor apaga para os dois (sinal `delete` ao par);
     * o recetor apaga só no seu aparelho.
     */
    fun deleteMessage(message: Message) {
        MessageRepository.removeMessage(message.id)
        com.lasvegas.secmess.repository.OutboundQueue.remove(message.id)
        if (message.senderId == currentUserId) {
            val payload = CryptoManager.encrypt(message.id.toByteArray(), localKey)
            com.lasvegas.secmess.repository.OutboundQueue.enqueue(
                Message(
                    id = "del_" + java.util.UUID.randomUUID().toString(),
                    senderId = currentUserId,
                    receiverId = targetUserId,
                    ciphertext = payload,
                    timestamp = System.currentTimeMillis(),
                    type = "delete",
                )
            )
        }
    }
    // Abrir esta conversa repõe o contador de avisos deste contacto.
    LaunchedEffect(targetUserId) {
        com.lasvegas.secmess.util.NotificationHelper.seen(context, targetUserId)
    }

    // Auto-destruction: 8 h depois da entrega se não foi vista; 5 min depois de vista.
    LaunchedEffect(messages) {
        while (true) {
            val now = System.currentTimeMillis()
            val queuedIds = com.lasvegas.secmess.repository.OutboundQueue.pending.value.map { it.id }.toSet()
            val toDelete = messages.filter { msg ->
                val isRelevant = (msg.senderId == targetUserId && msg.receiverId == currentUserId) ||
                                 (msg.senderId == currentUserId && msg.receiverId == targetUserId)
                if (!isRelevant) return@filter false
                // Em fila de saída (sem ack) fica até 12 h; não é apagada pelo TTL de conversa.
                if (msg.id in queuedIds) return@filter false
                now > MessageRepository.expiresAt(msg)
            }
            toDelete.forEach { MessageRepository.removeMessage(it.id) }
            delay(5000) // Check every 5s
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            if (!isInsideHub) {
                TopAppBar(
                    title = { 
                        Column {
                            Text(displayTargetName)
                            Text(if (isOnline) "ONLINE" else "OFFLINE", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                    }
                )
            }
        },
        bottomBar = {
            BottomAppBar {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        val isAdmin = currentUserId == "admin_001"
                        if (isInsideUnsafeZone && !isAdmin) {
                            Toast.makeText(context, "Security Protocol: Location Restricted", Toast.LENGTH_LONG).show()
                            return@IconButton
                        }
                        val permissionCheck = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                            val loc = LocationMonitor.currentLocation.value
                            if (loc != null) {
                                // geo: marker (no visible link) -> rendered as a tappable map card
                                onSendMessage("geo:${loc.latitude},${loc.longitude}")
                            } else {
                                Toast.makeText(context, "Fetching location, please wait...", Toast.LENGTH_SHORT).show()
                                LocationMonitor.startMonitoring(context)
                            }
                        } else {
                            val activity = context as? android.app.Activity
                            val showRationale = activity?.let { 
                                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
                            } ?: false
                            
                            if (showRationale) {
                                Toast.makeText(context, "Location permission is needed to share your position.", Toast.LENGTH_LONG).show()
                                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                Toast.makeText(context, "Security Protocol: Location Restricted by User. Enable in Settings.", Toast.LENGTH_LONG).show()
                                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        }
                    }) { Icon(Icons.Filled.LocationOn, null) }
                    TextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (text.isNotBlank()) { onSendMessage(text); text = "" } }) {
                        Icon(Icons.AutoMirrored.Filled.Send, null)
                    }
                }
            }
        }
    ) { padding ->
        val chatMessages = remember(messages) {
            messages.filter {
                (it.senderId == currentUserId && it.receiverId == targetUserId) ||
                (it.senderId == targetUserId && it.receiverId == currentUserId)
            }
                // A ordem é a do sender, não a da chegada: mensagens que estiveram
                // em fila podem chegar trocadas; o timestamp (monotónico) repõe.
                .sortedWith(compareBy({ it.timestamp }, { it.id }))
                .reversed()
        }
        
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), reverseLayout = true) {
            items(chatMessages) { msg ->
                MessageItem(
                    msg, currentUserId, currentLocale, localKey,
                    onLinkClick = { uriHandler.openUri(it) },
                    onDelete = { deleteMessage(msg) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: Message,
    currentUserId: String,
    locale: Locale,
    localKey: ByteArray,
    onLinkClick: (String) -> Unit,
    onDelete: () -> Unit = {},
) {
    val isMe = message.senderId == currentUserId
    val timeStr = SimpleDateFormat("HH:mm", locale).format(Date(message.timestamp))

    // Relógio ao vivo: 8 h desde a entrega (não vista) ou 5 min desde que foi vista.
    // Em fila de saída, conta o TTL de 12 h da fila (e não explode).
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(message.id) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val queuedAt = if (isMe && !message.delivered) {
        com.lasvegas.secmess.repository.OutboundQueue.queuedAt(message.id)
    } else null
    val isQueued = queuedAt != null
    val totalMs = when {
        isQueued -> com.lasvegas.secmess.repository.OutboundQueue.TTL_MS
        message.readTimestamp != null -> MessageRepository.READ_TTL_MS
        else -> MessageRepository.UNREAD_TTL_MS
    }
    val startAt = when {
        isQueued -> queuedAt!!
        message.readTimestamp != null -> message.readTimestamp!!
        else -> message.deliveredAt ?: message.timestamp
    }
    val remainingMs = (totalMs - (now - startAt)).coerceAtLeast(0L)
    val secs = remainingMs / 1000
    val exploding = !isQueued && remainingMs in 1..2500
    val bubbleScale by animateFloatAsState(targetValue = if (exploding) 1.18f else 1f, label = "destroy")

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
        Surface(
            color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = if (isMe) 12.dp else 0.dp, bottomEnd = if (isMe) 0.dp else 12.dp),
            modifier = Modifier
                .graphicsLayer { scaleX = bubbleScale; scaleY = bubbleScale }
                .combinedClickable(onClick = {}, onDoubleClick = onDelete)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                val decrypted = try {
                    String(CryptoManager.decrypt(message.ciphertext, localKey))
                } catch (_: Exception) { message.ciphertext } // plain link or failed decrypt

                if (decrypted.startsWith("geo:")) {
                    LocationCard(geo = decrypted, isMe = isMe, messageId = message.id)
                } else if (decrypted.startsWith("http")) {
                    Text(
                        text = decrypted,
                        color = Color(0xFF00FFFF),
                        modifier = Modifier.clickable { onLinkClick(decrypted) },
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
                    )
                } else {
                    Text(text = decrypted, color = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary)
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.End)) {
                    val sub = (if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary).copy(alpha = 0.6f)
                    Text(text = timeStr, style = MaterialTheme.typography.labelSmall, color = sub)
                    Spacer(modifier = Modifier.width(6.dp))
                    // self-destruct countdown -> explosion in the final seconds
                    if (exploding) {
                        Text("💥", style = MaterialTheme.typography.labelSmall)
                    } else if (isQueued) {
                        // Em fila: tempo que ainda tem para ser entregue (máx. 12 h).
                        val h = secs / 3600
                        val m = (secs % 3600) / 60
                        val s = secs % 60
                        Text(
                            "⏳ $h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.labelSmall, color = sub
                        )
                    } else {
                        val h = secs / 3600
                        val m = (secs % 3600) / 60
                        val s = secs % 60
                        val clock = if (h > 0) {
                            "⏳ $h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
                        } else {
                            "⏱ $m:${s.toString().padStart(2, '0')}"
                        }
                        Text(clock, style = MaterialTheme.typography.labelSmall, color = sub)
                    }
                    // delivery status (sender only): waiting -> sent -> seen
                    if (isMe) {
                        Spacer(modifier = Modifier.width(4.dp))
                        when {
                            message.readTimestamp != null ->
                                Icon(Icons.Filled.DoneAll, null, modifier = Modifier.size(14.dp), tint = Color(0xFF00FFFF))   // seen (2 ticks)
                            message.delivered ->
                                Icon(Icons.Filled.Done, null, modifier = Modifier.size(14.dp), tint = sub)                    // sent/received (1 tick)
                            else ->
                                Icon(Icons.Filled.ArrowUpward, null, modifier = Modifier.size(14.dp), tint = sub)             // waiting for recipient online
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocationCard(geo: String, isMe: Boolean, messageId: String) {
    val context = LocalContext.current
    var boom by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (boom) 1.7f else 1f, label = "boomScale")
    val fade by animateFloatAsState(targetValue = if (boom) 0f else 1f, label = "boomAlpha")
    val fg = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary

    LaunchedEffect(boom) {
        if (boom) {
            delay(280)  // explode + fade, then open the map and remove the card
            val coords = geo.removePrefix("geo:").substringBefore("?").trim()
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$coords"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            try { context.startActivity(geoIntent) }
            catch (_: Exception) {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$coords")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Exception) {}
            }
            com.lasvegas.secmess.repository.MessageRepository.removeMessage(messageId)  // box disappears
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = fade }
            .border(1.dp, fg.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .clickable { boom = true }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = fg)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (boom) "💥" else "📍 Open location",
            color = fg,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
