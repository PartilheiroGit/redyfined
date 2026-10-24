package com.lasvegas.secmess.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPairingTest {
    private val onion = "a".repeat(56)
    private val psk = ByteArray(32) { (it + 1).toByte() }
    private val now = 1_800_000_000_000L

    private fun encode(
        from: String = "admin_001",
        to: String = "user_zhhhGnU=",
        ttlMs: Long = QrPairing.DEFAULT_TTL_MS,
    ): String? = QrPairing.encode(
        from = from,
        to = to,
        onion = onion,
        x25519 = "eDE=",
        signing = "c2ln",
        psk = psk,
        now = now,
        ttlMs = ttlMs,
    )

    @Test
    fun roundTrip() {
        val payload = encode()
        assertNotNull(payload)
        assertTrue(payload!!.startsWith(QrPairing.PREFIX))

        val scan = QrPairing.decode(payload, "user_zhhhGnU=", now + 1_000)
        assertTrue(scan is QrPairing.Scan.Ok)
        val ok = scan as QrPairing.Scan.Ok
        assertEquals("admin_001", ok.invite.from)
        assertEquals("user_zhhhGnU=", ok.invite.to)
        assertEquals("$onion.onion", ok.invite.onion)
        assertArrayEquals(psk, ok.psk)
    }

    @Test
    fun rejectsCodeForAnotherDevice() {
        val scan = QrPairing.decode(encode()!!, "user_other", now)
        assertTrue(scan is QrPairing.Scan.Error)
        assertEquals("this code is for another device", (scan as QrPairing.Scan.Error).reason)
    }

    @Test
    fun rejectsExpiredCode() {
        val scan = QrPairing.decode(encode(ttlMs = 1_000)!!, "user_zhhhGnU=", now + 2_000)
        assertTrue(scan is QrPairing.Scan.Error)
        assertEquals("code expired", (scan as QrPairing.Scan.Error).reason)
    }

    @Test
    fun rejectsForeignPayload() {
        val scan = QrPairing.decode("https://example.com", "user_zhhhGnU=", now)
        assertTrue(scan is QrPairing.Scan.Error)
    }

    @Test
    fun rejectsShortPsk() {
        assertNull(
            QrPairing.encode(
                from = "a", to = "b", onion = onion,
                x25519 = "eDE=", signing = "c2ln", psk = ByteArray(16), now = now,
            )
        )
    }

    @Test
    fun normalizesOnion() {
        assertEquals("$onion.onion", QrPairing.normalizeOnion(onion.uppercase()))
        assertEquals("$onion.onion", QrPairing.normalizeOnion("$onion.onion"))
        assertNull(QrPairing.normalizeOnion("too-short.onion"))
        assertNull(QrPairing.normalizeOnion("1".repeat(56)))
    }
}
