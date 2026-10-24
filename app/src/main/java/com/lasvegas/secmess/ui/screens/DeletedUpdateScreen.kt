package com.lasvegas.secmess.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lasvegas.secmess.crypto.KeyStoreHelper
import com.lasvegas.secmess.network.RelayConfig
import com.lasvegas.secmess.network.TorManager
import com.lasvegas.secmess.network.UpdateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 131 — conta removida do roster.
 *
 * O relay já só entrega o canal do Calendário e fecha a sessão. A app segura não pode voltar ao
 * hub/chat: depois do PIN, só instala o APK do Calendário (mesmo package, versão 1000) por cima.
 * O Calendário não tem Tor/relay/chat.
 */
@Composable
fun DeletedUpdateScreen(
    keyStoreHelper: KeyStoreHelper,
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by UpdateManager.progress.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    val apkUrl = remember {
        keyStoreHelper.getAgendaApkUrl()?.takeIf { it.isNotBlank() }
            ?: (RelayConfig.DOWNLOAD_BASE.trimEnd('/') + "/agenda/app.apk")
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Atualização necessária", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Esta conta foi removida da rede. Instala o Calendário para continuares a usar a agenda.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))

        if (progress != null) {
            val p = progress ?: -1f
            if (p < 0f) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { p.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (p < 0f) "A descarregar…" else "${(p * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                if (busy) return@Button
                busy = true
                error = ""
                status = "A ligar ao Tor…"
                scope.launch {
                    TorManager.start(context)
                    val port = withTimeoutOrNull(90_000L) { TorManager.socksPort.first { it != null } }
                    if (port == null) {
                        error = "Tor indisponível. Tenta outra vez."
                        status = ""
                        busy = false
                        return@launch
                    }
                    status = ""
                    UpdateManager.startUpdate(
                        context,
                        apkUrl,
                        onFailure = { message ->
                            error = message
                            busy = false
                        },
                    )
                }
            },
            enabled = !busy && progress == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy || progress != null) "A DESCARREGAR…" else "ATUALIZAR")
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(18.dp))
        TextButton(onClick = onExit) { Text("Sair") }
    }
}
