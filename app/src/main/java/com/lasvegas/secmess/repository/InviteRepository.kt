package com.lasvegas.secmess.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries the invite URL the server mints back to the admin/sub-admin UI.
 * The phone asks the relay (mint_invite); the server replies (invite_url) with the public
 * bootstrap URL to show as a QR. Minting also arms auto-approval on the relay, so this ONE
 * QR installs and authorizes the recruit (no second QR). Transient; nothing persisted.
 */
object InviteRepository {
    private val _url = MutableStateFlow<String?>(null)
    val url = _url.asStateFlow()
    fun set(u: String) { if (u.isNotBlank()) _url.value = u }
    fun clear() { _url.value = null }
}
