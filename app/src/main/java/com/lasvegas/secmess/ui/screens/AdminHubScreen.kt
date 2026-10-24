package com.lasvegas.secmess.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lasvegas.secmess.crypto.KeyStoreHelper
import com.lasvegas.secmess.crypto.UserTier
import com.lasvegas.secmess.repository.BlockRepository
import com.lasvegas.secmess.repository.MessageRepository
import kotlinx.coroutines.delay
import com.lasvegas.secmess.ui.components.OnGoingTitle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.lasvegas.secmess.ui.theme.SecMessTheme
import com.lasvegas.secmess.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminHubScreen(
    currentUserId: String,
    presenceMap: Map<String, Long>,
    connectionStatus: String,
    keyStoreHelper: KeyStoreHelper,
    onSelectUser: (String) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onBannerClick: () -> Unit = {},
    userStatusMap: Map<String, String> = emptyMap(),
) {
    val tier = remember(keyStoreHelper) { keyStoreHelper.getTier() }
    LaunchedEffect(keyStoreHelper) { BlockRepository.init(keyStoreHelper) }

    AdminHubContent(
        currentUserId = currentUserId,
        presenceMap = presenceMap,
        connectionStatus = connectionStatus,
        tier = tier,
        onSelectUser = onSelectUser,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        onBannerClick = onBannerClick,
        userStatusMap = userStatusMap,
        getLocalDisplayName = { id -> keyStoreHelper.getLocalDisplayName(id) },
        onSetDisplayName = { id, name -> keyStoreHelper.setLocalDisplayName(id, name) }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AdminHubContent(
    currentUserId: String,
    presenceMap: Map<String, Long>,
    connectionStatus: String,
    tier: UserTier,
    onSelectUser: (String) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onBannerClick: () -> Unit = {},
    userStatusMap: Map<String, String> = emptyMap(),
    getLocalDisplayName: (String) -> String,
    onSetDisplayName: (String, String) -> Unit
) {
    val isAdmin = currentUserId == "admin_001" || tier == UserTier.MASTER

    val appNameLabel = stringResource(R.string.app_name)
    val noContactsLabel = stringResource(R.string.no_contacts_online)

    val blockedSet by BlockRepository.blocked.collectAsState()
    val hiddenSet by BlockRepository.hiddenBy.collectAsState()
    val messages by MessageRepository.messages.collectAsState()

    // Bolinha de não lidas: mensagens recebidas deste contacto que ainda não foram vistas.
    val unreadBySender = remember(messages, currentUserId) {
        messages.asSequence()
            .filter {
                it.receiverId == currentUserId &&
                    it.senderId != currentUserId &&
                    it.readTimestamp == null
            }
            .groupingBy { it.senderId }
            .eachCount()
    }

    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(5000)
        }
    }

    // Long-press action target + rename + blocked-list dialog state.
    var menuForId by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showBlockedList by remember { mutableStateOf(false) }

    // Sorted contacts: online first, then by last-seen. Blocked users are hidden from the list.
    val allKnownIds = remember(presenceMap, userStatusMap, currentTime, currentUserId, blockedSet, hiddenSet) {
        val ids = (presenceMap.keys + userStatusMap.keys).asSequence()
            .distinct()
            .filter { it.isNotEmpty() && it != currentUserId && !blockedSet.contains(it) && !hiddenSet.contains(it) }
            .toList()
        ids.sortedByDescending { id ->
            val lastSeen = presenceMap[id] ?: 0L
            val status = userStatusMap[id] ?: "DORMANT"
            val isOnline = (currentTime - lastSeen) < 30000 && status != "DORMANT"
            if (isOnline) 1000L else lastSeen
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OnGoingTitle(
                        text = if (isAdmin) "O-n G-oing Mess" else appNameLabel,
                        isConnected = connectionStatus == "Connected",
                        onBannerClick = onBannerClick
                    )
                },
                actions = {
                    IconButton(onClick = { showBlockedList = true }) {
                        BadgedBox(badge = {
                            if (blockedSet.isNotEmpty()) Badge { Text("${blockedSet.size}") }
                        }) { Icon(Icons.Default.Block, contentDescription = "Blocked users") }
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, null) }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (allKnownIds.isEmpty()) {
                EmptyState(noContactsLabel, connectionStatus)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allKnownIds, key = { it }) { id ->
                        val lastSeen = presenceMap[id] ?: 0L
                        val status = userStatusMap[id] ?: "DORMANT"
                        val isOnline = (currentTime - lastSeen) < 30000
                        val isDormant = status == "DORMANT"
                        val name = getLocalDisplayName(id)

                        val dotColor = when (status) {
                            "ONLINE", "ACTIVE" -> Color(0xFF34C759)
                            "UNSAFE" -> if (isAdmin) Color(0xFFBB86FC) else Color(0xFFFF3B30)
                            "DORMANT" -> Color(0xFFFFC107)
                            else -> if (isOnline) Color(0xFF34C759) else Color(0xFF8E8E93)
                        }
                        val statusLabel = when {
                            isDormant -> "Dormant"
                            isOnline -> "Online"
                            else -> "Offline"
                        }

                        ContactCard(
                            name = name,
                            statusLabel = statusLabel,
                            dotColor = dotColor,
                            unread = unreadBySender[id] ?: 0,
                            onClick = { onSelectUser(id) },
                            onLongClick = { menuForId = id }
                        )
                    }
                }
            }
        }
    }

    // --- Long-press action sheet: Edit name / Block ---
    menuForId?.let { id ->
        AlertDialog(
            onDismissRequest = { menuForId = null },
            title = { Text(getLocalDisplayName(id)) },
            text = { Text("Choose an action for this contact.") },
            confirmButton = {
                TextButton(onClick = {
                    renameId = id; renameText = getLocalDisplayName(id); menuForId = null
                }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Edit name") }
            },
            dismissButton = {
                if (id != "admin_001") {
                    TextButton(onClick = {
                        BlockRepository.block(id); menuForId = null
                    }) {
                        Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text("Block", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    // --- Rename dialog ---
    renameId?.let { id ->
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("Edit name") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetDisplayName(id, renameText.trim()); renameId = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } }
        )
    }

    // --- Blocked users list (unblock from here) ---
    if (showBlockedList) {
        AlertDialog(
            onDismissRequest = { showBlockedList = false },
            title = { Text("Blocked users") },
            text = {
                if (blockedSet.isEmpty()) {
                    Text("No blocked users.", color = Color.Gray)
                } else {
                    Column {
                        blockedSet.forEach { id ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(getLocalDisplayName(id), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { BlockRepository.unblock(id) }) { Text("Unblock") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBlockedList = false }) { Text("Done") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactCard(
    name: String,
    statusLabel: String,
    dotColor: Color,
    unread: Int = 0,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with the contact's initial + a presence dot.
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name.trim().firstOrNull()?.uppercase() ?: "?",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(13.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .padding(2.dp)
                        .background(dotColor, CircleShape)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (unread > 0) {
                        Spacer(Modifier.width(8.dp))
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ) { Text(if (unread > 99) "99+" else "$unread") }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = dotColor)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.Gray)
        }
    }
}

@Composable
private fun EmptyState(noContactsLabel: String, connectionStatus: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Group, null, modifier = Modifier.size(64.dp), tint = Color.Gray.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(16.dp))
        Text(noContactsLabel, color = Color.Gray)
        if (connectionStatus != "Connected") {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Not connected", style = MaterialTheme.typography.labelMedium, color = Color.Red)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Using a VPN? It may be blocking the link. Try, in your VPN app:\n" +
                       "  • Turn OFF its ad/threat blocker\n" +
                       "     (NordVPN “Threat Protection”, Proton “NetShield”,\n" +
                       "      Surfshark “CleanWeb”, etc.)\n" +
                       "  • or exclude this app from the VPN (split tunneling)\n" +
                       "  • or switch VPN server\n" +
                       "Then come back here.",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AdminHubScreenPreview() {
    SecMessTheme {
        AdminHubContent(
            currentUserId = "admin_001",
            presenceMap = mapOf("user_1" to System.currentTimeMillis()),
            connectionStatus = "Connected",
            tier = UserTier.MASTER,
            onSelectUser = {},
            onBack = {},
            onOpenSettings = {},
            onBannerClick = {},
            userStatusMap = mapOf("user_1" to "ACTIVE"),
            getLocalDisplayName = { id -> "User $id" },
            onSetDisplayName = { _, _ -> }
        )
    }
}
