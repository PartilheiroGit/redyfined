package com.lasvegas.secmess.bootstrap

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lasvegas.secmess.BuildConfig
import com.lasvegas.secmess.network.RelayConfig
import com.lasvegas.secmess.network.TorManager
import com.lasvegas.secmess.network.UpdateManager
import com.lasvegas.secmess.ui.theme.SecMessTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Bootstrap mínimo da app do GitHub.
 *
 * Só existe para: mostrar o calendário, escolher/entrar o PIN, ligar ao canal de update
 * (onion de download via Tor embebido) e pedir autorização para instalar a app completa.
 * Não tem relay, chat, P2P, contactos, localização, câmara nem microfone.
 */
class BootstrapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SecMessTheme {
                BootstrapRoot()
            }
        }
    }
}

@Composable
private fun BootstrapRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("og_boot", Context.MODE_PRIVATE) }
    var savedPin by remember { mutableStateOf(prefs.getString("pin", "").orEmpty()) }
    var unlocked by remember { mutableStateOf(savedPin.isBlank()) }

    if (!unlocked) {
        PinScreen(savedPin) { pin ->
            if (savedPin.isBlank()) prefs.edit().putString("pin", pin).apply()
            savedPin = pin
            unlocked = true
        }
    } else {
        Home()
    }
}

@Composable
private fun PinScreen(savedPin: String, onUnlock: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val isNew = savedPin.isBlank()

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("O.n the G.o Callendar", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (isNew) "Escolhe um PIN para o calendário"
            else "Introduz o PIN",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); error = "" },
            label = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        if (isNew) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it.filter { c -> c.isDigit() }.take(8); error = "" },
                label = { Text("Repetir PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                when {
                    pin.length !in 4..8 -> error = "O PIN tem de ter 4 a 8 dígitos"
                    isNew && pin != confirm -> error = "Os PINs não coincidem"
                    !isNew && pin != savedPin -> error = "PIN errado"
                    else -> onUnlock(pin)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (isNew) "GUARDAR" else "ENTRAR") }
    }
}

@Composable
private fun Home() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("A preparar o canal…") }
    var available by remember { mutableStateOf<Int?>(null) }
    var checking by remember { mutableStateOf(false) }
    val progress by UpdateManager.progress.collectAsState()

    suspend fun check() {
        if (checking) return
        checking = true
        status = "A ligar ao Tor…"
        val result: Pair<Int?, String> = withContext(Dispatchers.IO) {
            try {
                TorManager.start(context)
                val port = withTimeoutOrNull(90_000L) { TorManager.socksPort.first { it != null } }
                if (port == null) {
                    return@withContext (null to "Tor indisponível")
                }
                val connection = TorManager.openConnection("${RelayConfig.DOWNLOAD_BASE}/version")
                connection.connectTimeout = 60_000
                connection.readTimeout = 60_000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val version = Regex("\"version\"\\s*:\\s*\"?(\\d+)")
                    .find(body)?.groupValues?.get(1)?.toIntOrNull()
                if (version == null) null to "Resposta inválida do canal" else version to "Canal em $version"
            } catch (t: Throwable) {
                null to (t.message ?: "Falha de rede")
            }
        }
        val (version, message) = result
        available = version?.takeIf { it > BuildConfig.VERSION_CODE }
        status = when {
            available != null -> "Atualização $version disponível"
            version != null -> "Já tens a versão mais recente ($version)"
            else -> message
        }
        checking = false
    }

    LaunchedEffect(Unit) { check() }

    val today = remember { Calendar.getInstance() }
    var monthOffset by remember { mutableStateOf(0) }
    val display = remember(monthOffset) {
        (today.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, monthOffset)
        }
    }
    val monthLabel = remember(display) {
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(display.time)
            .replaceFirstChar { it.uppercase() }
    }
    val firstColumn = (display.get(Calendar.DAY_OF_WEEK) + 5) % 7 // segunda=0
    val daysInMonth = display.getActualMaximum(Calendar.DAY_OF_MONTH)
    val todayDay = today.get(Calendar.DAY_OF_MONTH)
    val isCurrentMonth = display.get(Calendar.MONTH) == today.get(Calendar.MONTH) &&
        display.get(Calendar.YEAR) == today.get(Calendar.YEAR)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("O.n the G.o Callendar", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Canal de atualização",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { monthOffset-- }) { Text("‹", fontSize = 26.sp) }
            Text(monthLabel, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { monthOffset++ }) { Text("›", fontSize = 26.sp) }
        }

        Row(Modifier.fillMaxWidth()) {
            listOf("S", "T", "Q", "Q", "S", "S", "D").forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        var day = 1
        for (row in 0 until 6) {
            if (day > daysInMonth) break
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    Box(
                        modifier = Modifier.weight(1f).aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        if ((row > 0 || col >= firstColumn) && day <= daysInMonth) {
                            val current = day
                            val isToday = isCurrentMonth && current == todayDay
                            Text(
                                "$current",
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isToday) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            day++
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Atualização", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
                val currentProgress = progress
                if (currentProgress != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (currentProgress < 0f) 0f else currentProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { scope.launch { check() } },
                        enabled = !checking && currentProgress == null,
                    ) {
                        Text(if (checking) "A VERIFICAR…" else "VERIFICAR")
                    }
                    available?.let { code ->
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                UpdateManager.startUpdate(
                                    context,
                                    "${RelayConfig.DOWNLOAD_BASE}/app.apk",
                                    onFailure = { message ->
                                        Toast.makeText(context, "Falha: $message", Toast.LENGTH_LONG).show()
                                    },
                                )
                            },
                            enabled = currentProgress == null,
                        ) { Text("INSTALAR $code") }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Versão ${BuildConfig.VERSION_CODE}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
