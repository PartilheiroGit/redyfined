package com.lasvegas.secmess.repository

import com.lasvegas.secmess.crypto.KeyStoreHelper
import com.lasvegas.secmess.service.RelayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fully client-side, peer-to-peer blocking. NOTHING is stored on the relay.
 *
 *  - blocked  = users I chose to block (kept encrypted on THIS phone). I hide them.
 *  - hiddenBy = users who blocked ME (learned from an encrypted peer directive). I hide them too,
 *               which is what makes "if you block someone, he won't see you" work — the directive
 *               tells his phone to hide me.
 *
 * The relay only transiently forwards the encrypted directive (type "peer_ctl") like any other
 * message and learns nothing: no block list, no client info, ever.
 */
object BlockRepository {
    private val _blocked = MutableStateFlow<Set<String>>(emptySet())   // I blocked them
    private val _hiddenBy = MutableStateFlow<Set<String>>(emptySet())  // they blocked me
    val blocked = _blocked.asStateFlow()
    val hiddenBy = _hiddenBy.asStateFlow()
    private var helper: KeyStoreHelper? = null

    fun init(h: KeyStoreHelper) {
        helper = h
        _blocked.value = h.getBlockedUsers()
        _hiddenBy.value = h.getHiddenByUsers()
    }

    /** Anyone I should not see in my list / accept messages from. */
    fun isInvisible(id: String): Boolean = _blocked.value.contains(id) || _hiddenBy.value.contains(id)

    fun block(id: String) {
        if (id.isBlank() || id == "admin_001") return     // can't block the admin (oversight)
        helper?.blockUser(id)
        _blocked.value = _blocked.value + id
        RelayService.relayState.value?.sendPeerBlock(id, true)   // tell his phone to hide me
    }

    fun unblock(id: String) {
        helper?.unblockUser(id)
        _blocked.value = _blocked.value - id
        RelayService.relayState.value?.sendPeerBlock(id, false)  // tell his phone to show me again
    }

    // --- driven by incoming peer directives (handled in WebSocketRelay) ---
    fun onBlockedBy(id: String) {
        helper?.addHiddenBy(id)
        _hiddenBy.value = _hiddenBy.value + id
    }

    fun onUnblockedBy(id: String) {
        helper?.removeHiddenBy(id)
        _hiddenBy.value = _hiddenBy.value - id
    }
}
