package com.lasvegas.secmess.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lasvegas.secmess.MainActivity
import com.lasvegas.secmess.R
import com.lasvegas.secmess.crypto.KeyStoreHelper
import com.lasvegas.secmess.crypto.UserTier
import com.lasvegas.secmess.model.AgendaEvent
import com.lasvegas.secmess.repository.AgendaRepository
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Notificações disfarçadas de eventos de calendário.
 *
 * Cada contacto tem um evento fictício estável (título/hora/data aleatórios), inserido no
 * calendário local. A notificação mostra esse evento — nunca o remetente nem o texto real —
 * e o toque abre o calendário no dia marcado. Uma chamada aparece como “Reunião”.
 *
 * Canais: messages_v2 (som curto), messages_v2_silent, calls_v2 (toque em MediaPlayer).
 */
object NotificationHelper {
    private const val CH_MSG = "messages_v2"
    private const val CH_MSG_SILENT = "messages_v2_silent"
    private const val CH_CALL = "calls_v2"
    private const val PREFS = "og_notif"
    private const val EVENTS = "og_notif_events"
    private const val KEY_MSG = "n_msg_"
    private const val KEY_CALL = "n_call_"

    const val EXTRA_UID = "notif_uid"
    const val EXTRA_DATE = "notif_date"
    const val ACTION_DISMISS = "com.lasvegas.secmess.NOTIF_DISMISS"

    @Volatile var inMessenger = false

    private var ringer: MediaPlayer? = null
    @Volatile private var ringingUid: String? = null

    private data class Content(
        val uid: String,
        val title: String,
        val text: String,
        val date: String,
        val channel: String,
        val silent: Boolean,
    )

    private val contents = ConcurrentHashMap<String, Content>()

    private val TITLES = listOf(
        "Consulta médica", "Reunião de equipa", "Almoço", "Dentista", "Ginásio",
        "Aniversário", "Inspeção do carro", "Aula de condução", "Jantar", "Café",
        "Banco", "Farmácia", "Compromisso", "Formação", "Assembleia",
    )

    // ---- API ----

    fun onMessageReceived(context: Context, fromUid: String, fromName: String, preview: String) {
        if (fromUid.isBlank()) return
        val helper = KeyStoreHelper(context)
        val admin = helper.isAdmin() || helper.getTier() == UserTier.REFERRER
        val cap = if (admin) 2 else 3
        val n = bump(context, KEY_MSG + fromUid)
        val alert = n <= cap && !inMessenger
        val event = eventFor(context, fromUid, call = false)
        notifyContact(
            context = context,
            uid = fromUid,
            title = event.first,
            text = event.second,
            date = event.third,
            channel = if (alert) CH_MSG else CH_MSG_SILENT,
            silent = !alert,
        )
    }

    fun onCallReceived(context: Context, fromUid: String, fromName: String) {
        if (fromUid.isBlank()) return
        val helper = KeyStoreHelper(context)
        val admin = helper.isAdmin() || helper.getTier() == UserTier.REFERRER
        val cap = if (admin) 1 else 2
        val n = bump(context, KEY_CALL + fromUid)
        val ring = n <= cap && !inMessenger
        val event = eventFor(context, fromUid, call = true)
        notifyContact(
            context = context,
            uid = fromUid,
            title = event.first,
            text = event.second,
            date = event.third,
            channel = CH_CALL,
            silent = true, // o som é o MediaPlayer em loop
        )
        if (ring) startRinging(context, fromUid)
    }

    /** Abriu a conversa: repõe o contador de mensagens e limpa a notificação do contacto. */
    fun seen(context: Context, uid: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_MSG + uid).apply()
        contents.remove(uid)
        cancelContact(context, uid)
        if (ringingUid == uid) stopRinging()
    }

    fun seen(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        contents.clear()
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
        } catch (_: Exception) {}
        stopRinging()
    }

    /** Swipe/apagar: esquece o conteúdo para não voltar a aparecer no próximo unlock. */
    fun onDismissed(uid: String) {
        contents.remove(uid)
    }

    fun stopRinging() {
        try {
            ringer?.stop()
            ringer?.release()
        } catch (_: Exception) {}
        ringer = null
        ringingUid = null
    }

    // ---- internals ----

    private fun bump(context: Context, key: String): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, n).apply()
        return n
    }

    private fun contactId(uid: String): Int = ("ogc:" + uid).hashCode()

    private fun cancelContact(context: Context, uid: String) {
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(contactId(uid))
        } catch (_: Exception) {}
    }

    /**
     * Evento fictício estável por contacto: título, data e hora aleatórios; inserido no
     * calendário local. Devolve (título, texto da notificação, data).
     */
    private fun eventFor(context: Context, uid: String, call: Boolean): Triple<String, String, String> {
        val prefs = context.getSharedPreferences(EVENTS, Context.MODE_PRIVATE)
        val today = LocalDate.now()
        val stored = prefs.getString(uid, null)
        var title: String? = null
        var date: LocalDate? = null
        var time: LocalTime? = null

        if (!call && stored != null) {
            val parts = stored.split("|")
            if (parts.size == 3) {
                title = parts[1].ifBlank { null }
                date = runCatching { LocalDate.parse(parts[0]) }.getOrNull()
                time = runCatching { LocalTime.parse(parts[2]) }.getOrNull()
            }
        }
        // Mensagens mantêm o evento enquanto não for passado; chamadas são "agora".
        if (call || title == null || date == null || time == null || date.isBefore(today)) {
            title = if (call) "Reunião" else TITLES.random()
            date = if (call) today else today.plusDays(Random.nextInt(1, 8).toLong())
            time = if (call) LocalTime.now().withSecond(0).withNano(0) else randomTime()
            prefs.edit().putString(uid, "$date|$title|$time").apply()
        }

        val day = date ?: today
        val clock = time ?: LocalTime.NOON
        val eventTitle = title ?: "Compromisso"

        // Evento real no calendário local (id estável por contacto).
        try {
            AgendaRepository.ensureInit(context)
            AgendaRepository.upsertEvent(
                AgendaEvent(
                    id = "notif_$uid",
                    date = day.toString(),
                    title = eventTitle,
                    description = "",
                    time = clock.format(DateTimeFormatter.ofPattern("HH:mm")),
                )
            )
        } catch (_: Exception) {}

        val label = when (day) {
            today -> "Hoje"
            today.plusDays(1) -> "Amanhã"
            else -> day.format(DateTimeFormatter.ofPattern("d MMM", Locale("pt", "PT")))
        }
        val text = if (call) "A começar · ${clock.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                   else "$label · ${clock.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        return Triple(eventTitle, text, day.toString())
    }

    private fun randomTime(): LocalTime {
        val hour = Random.nextInt(8, 20)
        val minute = listOf(0, 15, 30, 45).random()
        return LocalTime.of(hour, minute)
    }

    private fun notifyContact(
        context: Context,
        uid: String,
        title: String,
        text: String,
        date: String,
        channel: String,
        silent: Boolean,
    ) {
        val content = Content(uid, title, text, date, channel, silent)
        contents[uid] = content
        render(context, content, alert = !silent)
    }

    private fun render(context: Context, c: Content, alert: Boolean) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_UID, c.uid)
            putExtra(EXTRA_DATE, c.date)
        }
        val pi = PendingIntent.getActivity(
            context, contactId(c.uid), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = Intent(context, NotificationDismissReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_UID, c.uid)
        }
        val deletePi = PendingIntent.getBroadcast(
            context, contactId(c.uid), dismiss,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Versão pública idêntica: é um evento de calendário, não há conteúdo a esconder.
        val publicVersion = NotificationCompat.Builder(context, CH_MSG_SILENT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val n = NotificationCompat.Builder(context, c.channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(pi)
            .setDeleteIntent(deletePi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .build()

        try {
            nm.notify(contactId(c.uid), n)
        } catch (_: Exception) {}
    }

    private fun startRinging(context: Context, uid: String) {
        stopRinging()
        try {
            val player = MediaPlayer.create(context, R.raw.call_v2) ?: return
            player.isLooping = true
            if (Build.VERSION.SDK_INT >= 21) {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            player.start()
            ringer = player
            ringingUid = uid
        } catch (_: Exception) {}
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.deleteNotificationChannel("calendar_reminders_channel") }

        val msgSound = Uri.parse("android.resource://${context.packageName}/${R.raw.msg_v2}")
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(CH_MSG, "Eventos", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(msgSound, attrs)
                enableVibration(true)
                description = "Eventos de calendário"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MSG_SILENT, "Eventos (silencioso)", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                description = "Eventos de calendário (silencioso)"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALL, "Eventos a começar", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
                description = "Eventos de calendário a começar"
            }
        )
    }
}
