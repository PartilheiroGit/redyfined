package com.lasvegas.secmess.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Estado do broadcast do admin (fan-out 1:1 cifrado).
 *
 * O painel guarda o texto, pede os destinatários ao servidor (`broadcast_targets`),
 * o relay cria uma mensagem por destinatário e o contador sobe com cada ack.
 */
data class BroadcastState(
    val text: String = "",
    val scope: String = "all",
    val waiting: Boolean = false,
    val total: Int = 0,
    val delivered: Int = 0,
    val ids: Map<String, String> = emptyMap(), // messageId -> target uid
    val acked: Set<String> = emptySet(),       // dedup de acks
    val startedAt: Long = 0L,
)

object BroadcastRepository {
    private val _state = MutableStateFlow(BroadcastState())
    val state = _state.asStateFlow()

    /** Chamado pelo painel antes de pedir os targets. */
    fun begin(text: String, scope: String) {
        _state.value = BroadcastState(
            text = text,
            scope = scope,
            waiting = true,
            startedAt = System.currentTimeMillis(),
        )
    }

    /** Texto à espera dos targets; vazio se não há broadcast em curso. */
    fun pendingText(): String = _state.value.let { if (it.waiting) it.text else "" }

    /** O relay criou as mensagens: total fixo, delivered a zero. */
    fun sent(ids: Map<String, String>) {
        _state.update {
            it.copy(
                waiting = false,
                total = ids.size,
                delivered = 0,
                ids = ids,
                startedAt = if (it.startedAt == 0L) System.currentTimeMillis() else it.startedAt,
            )
        }
    }

    fun fail() {
        _state.update { it.copy(waiting = false) }
    }

    /** Um destinatário confirmou (ack P2P ou delivery_ack). */
    fun onAck(messageId: String) {
        _state.update { s ->
            if (s.ids.containsKey(messageId) && messageId !in s.acked && s.delivered < s.total) {
                s.copy(delivered = s.delivered + 1, acked = s.acked + messageId)
            } else s
        }
    }

    fun reset() {
        _state.value = BroadcastState()
    }
}
