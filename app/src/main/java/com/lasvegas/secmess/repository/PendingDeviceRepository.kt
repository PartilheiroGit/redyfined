package com.lasvegas.secmess.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Devices that connected with the app but are NOT yet approved by the admin.
 * The server pushes a "join_request" (senderId = the device to approve) to every admin
 * socket, and a "join_cleared" when it's resolved/disconnected. In-memory only.
 * The admin authorizes/denies from Settings → sends approve_device/deny_device to the relay.
 */
data class PendingDevice(val id: String, val name: String)

object PendingDeviceRepository {
    private val _pending = MutableStateFlow<List<PendingDevice>>(emptyList())
    val pending = _pending.asStateFlow()

    fun add(id: String, name: String) {
        if (id.isBlank()) return
        _pending.update { list ->
            val existing = list.find { it.id == id }
            when {
                existing == null -> list + PendingDevice(id, name)
                // device already listed: update the display name once the user provides it
                name.isNotBlank() && existing.name != name -> list.map { if (it.id == id) it.copy(name = name) else it }
                else -> list
            }
        }
    }

    fun remove(id: String) {
        _pending.update { list -> list.filter { it.id != id } }
    }
}
