package com.lasvegas.secmess.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pending "new user" suggestions sent by sub-admins to the admin.
 * In-memory only (no disk). The admin authorizes (→ generate invite) or denies.
 */
data class Suggestion(val id: String, val from: String, val note: String)

object SuggestionRepository {
    private val _pending = MutableStateFlow<List<Suggestion>>(emptyList())
    val pending = _pending.asStateFlow()

    fun add(id: String, from: String, note: String) {
        _pending.update { list -> if (list.any { it.id == id }) list else list + Suggestion(id, from, note) }
    }

    fun remove(id: String) {
        _pending.update { list -> list.filter { it.id != id } }
    }
}
