package com.lasvegas.secmess.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries the invite URL the server mints back to the admin/sub-admin UI.
 * The phone asks the relay (mint_invite); the server replies (invite_url) with the public
 * bootstrap URL to show as a QR. Minting arms provisional entry on the relay: this ONE QR
 * installs, enters the download network and links the recruit to the inviter (admin, or
 * admin + sub-admin). The admin still presses AUTHORIZE before the recruit can communicate.
 * Transient; nothing persisted.
 */
object InviteRepository {
    private val _url = MutableStateFlow<String?>(null)
    val url = _url.asStateFlow()
    fun set(u: String) { if (u.isNotBlank()) _url.value = u }
    fun clear() { _url.value = null }
}
