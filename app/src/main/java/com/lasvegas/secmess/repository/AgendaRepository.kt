package com.lasvegas.secmess.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.lasvegas.secmess.model.AgendaEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AgendaRepository {
    private val _events = MutableStateFlow<List<AgendaEvent>>(emptyList())
    val events: StateFlow<List<AgendaEvent>> = _events.asStateFlow()

    private lateinit var sharedPrefs: android.content.SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            sharedPrefs = EncryptedSharedPreferences.create(
                "agenda_events_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            loadEvents()
        } catch (e: Exception) {
            // Decryption failed (e.g. Keystore reset or corrupted data)
            context.getSharedPreferences("agenda_events_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            // Retry once
            try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                sharedPrefs = EncryptedSharedPreferences.create(
                    "agenda_events_prefs",
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                _events.value = emptyList()
            } catch (_: Exception) {
                // Total failure - fallback to normal prefs (not ideal but avoids crash)
                sharedPrefs = context.getSharedPreferences("agenda_events_prefs_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun loadEvents() {
        val encoded = sharedPrefs.getString("events_list", null) ?: return
        try {
            val list = json.decodeFromString<List<AgendaEvent>>(encoded)
            _events.value = list
        } catch (e: Exception) {
            _events.value = emptyList()
        }
    }

    private fun saveEvents() {
        val encoded = json.encodeToString(_events.value)
        sharedPrefs.edit().putString("events_list", encoded).apply()
    }

    /** Garante que o repositório está pronto antes de ser usado fora da MainActivity. */
    fun ensureInit(context: Context) {
        if (!::sharedPrefs.isInitialized) init(context)
    }

    fun addEvent(event: AgendaEvent) {
        _events.update { it + event }
        saveEvents()
    }

    /** Substitui o evento com o mesmo id (usado pelos eventos-disfarce das notificações). */
    fun upsertEvent(event: AgendaEvent) {
        _events.update { list -> list.filterNot { it.id == event.id } + event }
        saveEvents()
    }

    fun deleteEvent(eventId: String) {
        _events.update { it.filter { e -> e.id != eventId } }
        saveEvents()
    }

    fun getEventsForDate(date: String): List<AgendaEvent> {
        return _events.value.filter { it.date == date }
    }
    
    fun clearAll() {
        _events.value = emptyList()
        sharedPrefs.edit().remove("events_list").apply()
    }
}
