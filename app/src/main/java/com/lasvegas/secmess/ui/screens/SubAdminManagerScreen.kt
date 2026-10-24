package com.lasvegas.secmess.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lasvegas.secmess.crypto.KeyStoreHelper
import com.lasvegas.secmess.model.Message
import com.lasvegas.secmess.repository.BroadcastRepository
import com.lasvegas.secmess.repository.BroadcastState
import com.lasvegas.secmess.repository.SubAdminInfo
import com.lasvegas.secmess.repository.SubAdminRepository
import com.lasvegas.secmess.repository.SubAdminSnapshot
import com.lasvegas.secmess.repository.SubAdminUser
import com.lasvegas.secmess.repository.SuggestionRepository
import com.lasvegas.secmess.service.RelayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Painel do admin para a hierarquia 111: até 5 sub-admins, cohort, matriz 5x5 e pedidos.
 * O servidor é a autoridade; o painel envia comandos e volta a pedir o snapshot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAdminManagerScreen(
    keyStoreHelper: KeyStoreHelper,
    onBack: () -> Unit = {}
) {
    val admin = keyStoreHelper.isAdmin()
    val snapshot by SubAdminRepository.state.collectAsState()
    val pending by SuggestionRepository.pending.collectAsState()
    val bcast by BroadcastRepository.state.collectAsState()
    val scope = rememberCoroutineScope()

    fun send(type: String, payload: String = "", receiver: String = "SYSTEM") {
        val relay = RelayService.relayState.value ?: return
        relay.sendMessage(
            Message(
                id = "${type}_${System.currentTimeMillis()}",
                senderId = keyStoreHelper.getUserId(),
                receiverId = receiver,
                ciphertext = payload,
                timestamp = System.currentTimeMillis(),
                type = type,
            )
        )
    }

    fun refresh(afterMs: Long = 0) {
        scope.launch {
            if (afterMs > 0) delay(afterMs)
            send("subadmins")
        }
    }

    LaunchedEffect(Unit) { if (admin) refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sub-admins") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (admin) IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!admin) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Painel do admin", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "O teu papel é definido pelo servidor. Este ecrã só está disponível na conta admin.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                return@Scaffold
            }

            val snap = snapshot
            if (snap == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("A carregar a hierarquia…")
                }
                return@Scaffold
            }

            Header(snap)
            BroadcastCard(bcast) { text, scopeName ->
                BroadcastRepository.begin(text, scopeName)
                send("broadcast_targets", scopeName)
            }
            pending.forEach { s ->
                InviteRequest(
                    from = s.from,
                    note = s.note,
                    name = snap.users.firstOrNull { it.id == s.from }?.name.orEmpty(),
                    onAccept = {
                        send("mint_invite_for", "", s.from)
                        SuggestionRepository.remove(s.id)
                        refresh(600)
                    },
                    onDismiss = { SuggestionRepository.remove(s.id) },
                )
            }
            SubAdmins(snap,
                onReach = { sa, on ->
                    send("set_admin_reach", buildJsonObject { put("sub", sa); put("on", on) }.toString())
                    refresh(600)
                },
                onDemote = { sa ->
                    send("set_role", buildJsonObject { put("uid", sa); put("role", "user") }.toString())
                    refresh(700)
                },
                onUnassign = { sa, user ->
                    send("unassign_user", buildJsonObject { put("sub", sa); put("user", user) }.toString())
                    refresh(600)
                },
                onAssign = { sa, user ->
                    send("assign_user", buildJsonObject { put("sub", sa); put("user", user) }.toString())
                    refresh(600)
                },
            )
            Matrix(snap) { a, b, on ->
                send("set_sa_pair", buildJsonObject { put("a", a); put("b", b); put("on", on) }.toString())
                refresh(600)
            }
            UserPool(snap) { user ->
                send("set_role", buildJsonObject { put("uid", user); put("role", "sa") }.toString())
                refresh(800)
            }
            Text(
                "Matriz SA↔SA default OFF; o admin vê todos. Convites de user passam por um pedido do SA e pelo aceite do admin.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Header(snap: SubAdminSnapshot) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Sub-admins", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${snap.subs.size}/${snap.max} · users no pool: ${snap.users.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Default.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SubAdmins(
    snap: SubAdminSnapshot,
    onReach: (String, Boolean) -> Unit,
    onDemote: (String) -> Unit,
    onUnassign: (String, String) -> Unit,
    onAssign: (String, String) -> Unit,
) {
    if (snap.subs.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Text(
                "Ainda não há sub-admins. Promove um user no fim deste ecrã.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    val names = snap.users.associate { it.id to it.name }
    snap.subs.forEach { sa ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sa.name.ifBlank { sa.id }, fontWeight = FontWeight.SemiBold)
                        Text(sa.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("vê o admin", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(6.dp))
                    Switch(checked = sa.reach, onCheckedChange = { onReach(sa.id, it) })
                }

                Text("Cohort (${sa.cohort.size})", style = MaterialTheme.typography.labelMedium)
                if (sa.cohort.isEmpty()) {
                    Text("Sem users atribuídos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sa.cohort.forEach { uid ->
                            InputChip(
                                selected = false,
                                onClick = { onUnassign(sa.id, uid) },
                                label = { Text(names[uid]?.ifBlank { uid } ?: uid, style = MaterialTheme.typography.labelSmall) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = "Remover", modifier = Modifier.size(14.dp))
                                },
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssignMenu(sa, snap.users, onAssign)
                    TextButton(onClick = { onDemote(sa.id) }) { Text("Rebaixar") }
                }
            }
        }
    }
}

@Composable
private fun AssignMenu(sa: SubAdminInfo, users: List<SubAdminUser>, onAssign: (String, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val available = users.filter { it.id !in sa.cohort }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = available.isNotEmpty()) {
            Text("Atribuir user")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (available.isEmpty()) {
                DropdownMenuItem(text = { Text("Sem users livres") }, onClick = { open = false })
            } else {
                available.forEach { u ->
                    DropdownMenuItem(
                        text = { Text(u.name.ifBlank { u.id }) },
                        onClick = {
                            open = false
                            onAssign(sa.id, u.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Matrix(snap: SubAdminSnapshot, onPair: (String, String, Boolean) -> Unit) {
    if (snap.subs.size < 2) return
    Text("Matriz SA ↔ SA", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(
        "Default OFF. Ligar aqui permite que os dois SAs se vejam e falem.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val names = snap.subs.associate { it.id to it.name.ifBlank { it.id } }
    for (i in snap.subs.indices) {
        for (j in i + 1 until snap.subs.size) {
            val a = snap.subs[i].id
            val b = snap.subs[j].id
            val on = snap.matrix[a]?.contains(b) == true || snap.matrix[b]?.contains(a) == true
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${names[a]} ↔ ${names[b]}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = on, onCheckedChange = { onPair(a, b, it) })
            }
        }
    }
}

@Composable
private fun UserPool(snap: SubAdminSnapshot, onPromote: (String) -> Unit) {
    Text("Users", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    if (snap.users.isEmpty()) {
        Text("Sem users no pool.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val full = snap.subs.size >= snap.max
    snap.users.forEach { u ->
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(u.name.ifBlank { u.id }, style = MaterialTheme.typography.bodyMedium)
                    Text(u.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { onPromote(u.id) }, enabled = !full) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Promover")
                }
            }
        }
    }
    if (full) {
        Text(
            "Limite de ${snap.max} sub-admins atingido.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun BroadcastCard(state: BroadcastState, onSend: (String, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Aviso do admin", fontWeight = FontWeight.SemiBold)
            Text(
                "Fan-out 1:1 cifrado; a contagem de entregue sobe com os acks.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Mensagem") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val t = text.trim()
                        if (t.isNotEmpty()) {
                            onSend(t, "all")
                            text = ""
                        }
                    },
                    enabled = text.isNotBlank() && !state.waiting,
                ) { Text("Enviar a todos") }
                OutlinedButton(
                    onClick = {
                        val t = text.trim()
                        if (t.isNotEmpty()) {
                            onSend(t, "sas")
                            text = ""
                        }
                    },
                    enabled = text.isNotBlank() && !state.waiting,
                ) { Text("Enviar a sub-admins") }
            }
            when {
                state.waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("A preparar destinatários…", style = MaterialTheme.typography.bodySmall)
                }
                state.total > 0 -> {
                    Text(
                        if (state.delivered >= state.total) "Entregue a todos (${state.total})"
                        else "Entregue ${state.delivered}/${state.total}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
                    ) {
                        val frac = (state.delivered.toFloat() / state.total).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .fillMaxWidth(frac)
                                .height(6.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                        )
                    }
                }
                state.startedAt > 0 -> Text(
                    "Sem destinatários.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InviteRequest(from: String, note: String, name: String, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pedido de ${name.ifBlank { from }}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(note, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text("Aceitar convite") }
                TextButton(onClick = onDismiss) { Text("Dispensar") }
            }
        }
    }
}
