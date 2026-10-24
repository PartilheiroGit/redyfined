package com.lasvegas.secmess.repository

import com.lasvegas.secmess.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MessageRepository: In-memory transient storage.
 * Ensures that messages are NEVER written to disk and are cleared after a TTL.
 *
 * TTL (127):
 *  - entregue e **não vista**: 2 h desde a entrega;
 *  - **vista** (readTimestamp): 5 min desde que foi vista — depois explode e desaparece;
 *  - sem recetor / em fila de saída: 12 h (OutboundQueue); não conta para as 2 h.
 */
object MessageRepository {
    /** Mensagem entregue mas ainda não vista (2 h). */
    const val UNREAD_TTL_MS = 2L * 60 * 60 * 1000

    /** Mensagem vista: 5 min até desaparecer. */
    const val READ_TTL_MS = 5L * 60 * 1000

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages = _messages.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)

    /** Quando é que esta mensagem desaparece (relógio local). */
    fun expiresAt(message: Message): Long =
        message.readTimestamp?.plus(READ_TTL_MS)
            ?: ((message.deliveredAt ?: message.timestamp) + UNREAD_TTL_MS)

    fun addMessage(message: Message) {
        _messages.update { it + message }
        if (message.delivered) {
            scheduleExpiry(message.id, message.deliveredAt ?: System.currentTimeMillis())
        }
    }

    /**
     * Marca como vista (recetor) e agenda a destruição 5 min depois.
     * Repetir a chamada não reinicia o contador.
     */
    fun markAsRead(messageId: String) {
        val now = System.currentTimeMillis()
        var changed = false
        _messages.update { list ->
            list.map {
                if (it.id == messageId && it.readTimestamp == null) {
                    changed = true
                    it.copy(readTimestamp = now)
                } else it
            }
        }
        if (changed) scheduleReadExpiry(messageId)
    }

    fun markAsDelivered(messageId: String) {
        var deliveredAt: Long? = null
        _messages.update { list ->
            list.map {
                if (it.id == messageId) {
                    val at = it.deliveredAt ?: System.currentTimeMillis()
                    deliveredAt = at
                    it.copy(delivered = true, deliveredAt = at)
                } else it
            }
        }
        deliveredAt?.let { scheduleExpiry(messageId, it) }
    }

    /**
     * O recetor leu: o sender também conta 5 min a partir daqui.
     */
    fun markAsReadRemote(messageId: String, timestamp: Long) {
        var changed = false
        _messages.update { list ->
            list.map {
                if (it.id == messageId && it.readTimestamp == null) {
                    changed = true
                    it.copy(readTimestamp = timestamp)
                } else it
            }
        }
        if (changed) scheduleReadExpiry(messageId)
    }

    fun removeMessage(messageId: String) {
        _messages.update { list -> list.filter { it.id != messageId } }
    }

    /** Instant wipe of all session messages. */
    fun clearAll() {
        _messages.value = emptyList()
    }

    /** 2 h depois da entrega, se entretanto não foi vista nem está em fila. */
    private fun scheduleExpiry(messageId: String, deliveredAt: Long) {
        scope.launch {
            val wait = (deliveredAt + UNREAD_TTL_MS - System.currentTimeMillis()).coerceAtLeast(0L)
            if (wait > 0) delay(wait)
            val message = _messages.value.firstOrNull { it.id == messageId } ?: return@launch
            if (message.readTimestamp == null && OutboundQueue.queuedAt(messageId) == null) {
                removeMessage(messageId)
            }
        }
    }

    private fun scheduleReadExpiry(messageId: String) {
        scope.launch {
            delay(READ_TTL_MS)
            removeMessage(messageId)
        }
    }
}
