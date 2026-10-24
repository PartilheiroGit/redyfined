package com.lasvegas.secmess.agenda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.lasvegas.secmess.crypto.KeyStoreHelper
import com.lasvegas.secmess.repository.AgendaRepository
import com.lasvegas.secmess.ui.screens.CalendarScreen
import com.lasvegas.secmess.ui.theme.SecMessTheme
import java.io.File

/**
 * App Calendário (canal especial dos users apagados).
 *
 * Mesmo package/assinatura da app segura; versão 1000 para instalar por cima. Não tem Tor,
 * relay, chat, contactos, P2P, localização, câmara nem microfone. No primeiro arranque limpa
 * os dados da app segura (mensagens, chaves, prefs, DBs) e deixa só a agenda nova.
 */
class AgendaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wipeSecureDataOnce()
        // UI em português por omissão; o Settings do calendário pode mudar.
        runCatching { KeyStoreHelper(this).setLanguage("pt") }
        AgendaRepository.init(this)
        setContent {
            SecMessTheme {
                AgendaRoot()
            }
        }
    }

    private fun wipeSecureDataOnce() {
        val marker = File(filesDir, "agenda_ready")
        if (marker.exists()) return
        val dataDir = applicationInfo.dataDir
        listOf("shared_prefs", "databases", "files", "cache", "no_backup").forEach { name ->
            try {
                File(dataDir, name).listFiles()?.forEach { it.deleteRecursively() }
            } catch (_: Exception) {}
        }
        try {
            marker.parentFile?.mkdirs()
            marker.writeText("1")
        } catch (_: Exception) {}
    }
}

@Composable
private fun AgendaRoot() {
    val context = LocalContext.current
    val helper = remember { KeyStoreHelper(context) }
    var hasPin by remember { mutableStateOf(helper.hasPin()) }

    CalendarScreen(
        hasPin = hasPin,
        keyStoreHelper = helper,
        onPinSet = { pin ->
            helper.storePin(pin)
            hasPin = true
        },
        onPinVerify = { pin -> helper.verifyPin(pin) },
        onUnlockSuccess = {},
        isInsideUnsafeZone = false,
        isRevoked = false,
    )
}
