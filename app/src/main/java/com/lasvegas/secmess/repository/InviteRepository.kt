package com.lasvegas.secmess.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries the one-time master-invite URL the server mints back to the admin UI.
 * The admin phone asks the relay (mint_invite); the server replies (invite_url) with a
 * https://<relay>/i/<token> link that the admin shows as a QR. Transient; nothing persisted.
 */
object InviteRepository {
    private val _url = MutableStateFlow<String?>(null)
    val url = _url.asStateFlow()
    fun set(u: String) { if (u.isNotBlank()) _url.value = u }
    fun clear() { _url.value = null }
}
