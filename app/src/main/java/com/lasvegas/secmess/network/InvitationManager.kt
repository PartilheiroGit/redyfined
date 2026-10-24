package com.lasvegas.secmess.network

import com.lasvegas.secmess.model.Invitation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

object InvitationManager {
    private val json = Json { ignoreUnknownKeys = true }
    
    // System-level hardcoded key for QR obfuscation
    private val SYSTEM_OBFUSCATION_KEY = "system-sec-v1-obfuscation-key-32".toByteArray()
    private val SYSTEM_IV = "iv-sec-v1-system".toByteArray()
    private const val OBFUSCATION_PREFIX = "SEC_V1_"

    fun generateInvitationPayload(invitation: Invitation): String {
        return try {
            val rawJson = json.encodeToString(invitation)
            val keySpec = SecretKeySpec(SYSTEM_OBFUSCATION_KEY, "AES")
            val ivSpec = IvParameterSpec(SYSTEM_IV)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(rawJson.toByteArray())
            OBFUSCATION_PREFIX + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            "ERROR"
        }
    }

    fun parseInvitationPayload(payload: String): Invitation? {
        return try {
            if (!payload.startsWith(OBFUSCATION_PREFIX)) return null
            val encryptedBase64 = payload.removePrefix(OBFUSCATION_PREFIX)
            val encrypted = Base64.decode(encryptedBase64, Base64.DEFAULT)
            
            val keySpec = SecretKeySpec(SYSTEM_OBFUSCATION_KEY, "AES")
            val ivSpec = IvParameterSpec(SYSTEM_IV)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val rawJson = String(cipher.doFinal(encrypted))

            json.decodeFromString<Invitation>(rawJson)
        } catch (e: Exception) {
            null
        }
    }
}
