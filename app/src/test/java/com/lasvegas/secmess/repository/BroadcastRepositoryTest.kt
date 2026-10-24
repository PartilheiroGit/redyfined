package com.lasvegas.secmess.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastRepositoryTest {
    @Test
    fun countsOnlyKnownAcksOnce() {
        BroadcastRepository.reset()
        BroadcastRepository.begin("aviso", "all")
        BroadcastRepository.sent(mapOf("m1" to "sa1", "m2" to "sa2"))

        BroadcastRepository.onAck("m1")
        BroadcastRepository.onAck("m1") // duplicado: não conta duas vezes
        BroadcastRepository.onAck("desconhecido")

        val state = BroadcastRepository.state.value
        assertEquals(1, state.delivered)
        assertEquals(2, state.total)
        assertTrue(state.ids.containsKey("m1"))
    }

    @Test
    fun noTargetsEndsWithoutWaiting() {
        BroadcastRepository.reset()
        BroadcastRepository.begin("aviso", "sas")
        BroadcastRepository.sent(emptyMap())
        val state = BroadcastRepository.state.value
        assertEquals(0, state.total)
        assertEquals(0, state.delivered)
        assertTrue(!state.waiting)
    }
}
