package com.lasvegas.secmess.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SubAdminRepositoryTest {
    @Test
    fun parsesSnapshot() {
        val raw = """
            {
              "subs": [
                {"id":"user_a","name":"Ana","reach":true,"cohort":["user_u1","user_u2"]}
              ],
              "users": [
                {"id":"user_u1","name":"U1","role":"user"},
                {"id":"user_u2","name":"U2","role":"user"}
              ],
              "matrix": {"user_a":["user_b"]},
              "max": 5
            }
        """.trimIndent()

        SubAdminRepository.apply(raw)
        val snap = SubAdminRepository.state.value
        assertNotNull(snap)
        assertEquals(1, snap!!.subs.size)
        assertEquals("Ana", snap.subs[0].name)
        assertEquals(2, snap.subs[0].cohort.size)
        assertEquals(2, snap.users.size)
        assertEquals(listOf("user_b"), snap.matrix["user_a"])
        assertEquals(5, snap.max)
    }

    @Test
    fun ignoresInvalidPayload() {
        SubAdminRepository.clear()
        SubAdminRepository.apply("not-json")
        assertEquals(null, SubAdminRepository.state.value)
    }
}
