package com.lasvegas.secmess.repository

import com.lasvegas.secmess.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTtlTest {
    private fun message(deliveredAt: Long?, readAt: Long?, ts: Long = 1_000L) = Message(
        id = "m1",
        senderId = "a",
        receiverId = "b",
        ciphertext = "x",
        timestamp = ts,
        delivered = deliveredAt != null,
        deliveredAt = deliveredAt,
        readTimestamp = readAt,
    )

    @Test
    fun unreadExpiresTwoHoursAfterDelivery() {
        val m = message(deliveredAt = 10_000L, readAt = null)
        assertEquals(10_000L + MessageRepository.UNREAD_TTL_MS, MessageRepository.expiresAt(m))
    }

    @Test
    fun seenExpiresFiveMinutesAfterRead() {
        val m = message(deliveredAt = 10_000L, readAt = 20_000L)
        assertEquals(20_000L + MessageRepository.READ_TTL_MS, MessageRepository.expiresAt(m))
    }

    @Test
    fun fallsBackToSendTimestampWhenNotDelivered() {
        val m = message(deliveredAt = null, readAt = null, ts = 5_000L)
        assertEquals(5_000L + MessageRepository.UNREAD_TTL_MS, MessageRepository.expiresAt(m))
    }
}
