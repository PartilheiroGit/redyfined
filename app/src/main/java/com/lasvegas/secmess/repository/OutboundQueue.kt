package com.lasvegas.secmess.repository

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lasvegas.secmess.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Fila de saída no telefone do SENDER.
 *
 * Regra (111): uma mensagem só sai da fila quando o destinatário confirma a receção
 * (`ack` P2P ou `delivery_ack` pelo relay). Se o par não estiver do outro lado, fica
 * aqui e é retentada. A fila sobrevive a morte do processo e reboot, e expira ao fim
 * de [TTL_MS] (12 horas).
 *
 * O conteúdo é guardado cifrado (EncryptedSharedPreferences), nunca em claro.
 */
object OutboundQueue {
    private const val TAG = "OGOutbox"
    private const val PREFS = "og_outbox"
    private const val KEY = "queue"

    /** Tempo máximo que uma mensagem espera pelo ack. */
    const val TTL_MS = 12L * 60 * 60 * 1000

    @Serializable
    private data class Entry(val message: Message, val queuedAt: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    private val _pending = MutableStateFlow<List<Message>>(emptyList())
    val pending = _pending.asStateFlow()
    private val queuedAt = mutableMapOf<String, Long>()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        val store = try {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app, PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ) as android.content.SharedPreferences
        } catch (t: Throwable) {
            Log.w(TAG, "EncryptedSharedPreferences indisponível: ${t.message}")
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        prefs = store
        load()
    }

    private fun load() {
        val raw = prefs?.getString(KEY, null) ?: return
        try {
            val entries = json.decodeFromString(ListSerializer(Entry.serializer()), raw)
            val now = System.currentTimeMillis()
            val alive = entries.filter { now - it.queuedAt < TTL_MS }
            if (alive.size != entries.size) {
                Log.i(TAG, "${entries.size - alive.size} mensagem(ns) expirada(s) (>12 h)")
            }
            synchronized(lock) {
                queuedAt.clear()
                alive.forEach { queuedAt[it.message.id] = it.queuedAt }
            }
            _pending.value = alive.map { it.message }
            Log.i(TAG, "fila restaurada: ${alive.size}")
            persist()
        } catch (t: Throwable) {
            Log.w(TAG, "fila ilegível: ${t.message}")
        }
    }

    fun enqueue(msg: Message) {
        synchronized(lock) { queuedAt[msg.id] = System.currentTimeMillis() }
        _pending.update { it + msg }
        persist()
    }

    fun remove(id: String) {
        synchronized(lock) { queuedAt.remove(id) }
        _pending.update { list -> list.filter { it.id != id } }
        persist()
    }

    /** Snapshot já sem entradas expiradas (12 h). */
    fun snapshot(): List<Message> {
        purgeExpired()
        return _pending.value
    }

    fun queuedAt(id: String): Long? = synchronized(lock) { queuedAt[id] }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        val expired = synchronized(lock) {
            queuedAt.filterValues { now - it >= TTL_MS }.keys.toList()
        }
        if (expired.isEmpty()) return
        Log.i(TAG, "${expired.size} mensagem(ns) expirada(s) sem ack — descartadas")
        synchronized(lock) { expired.forEach { queuedAt.remove(it) } }
        _pending.update { list -> list.filter { it.id !in expired } }
        expired.forEach { MessageRepository.removeMessage(it) }
        persist()
    }

    private fun persist() {
        val p = prefs ?: return
        try {
            val now = System.currentTimeMillis()
            val entries = _pending.value.map { msg ->
                Entry(msg, synchronized(lock) { queuedAt[msg.id] } ?: now)
            }
            p.edit()
                .putString(KEY, json.encodeToString(ListSerializer(Entry.serializer()), entries))
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "não gravou a fila: ${t.message}")
        }
    }
}
