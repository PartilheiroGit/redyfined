package com.lasvegas.secmess.crypto

import android.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.*
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CryptoManager: Core Security Module for End-to-End Encryption.
 * Implements X25519 for Key Exchange and AES-256-GCM for Authenticated Encryption.
 * Optimized for Zero-Footprint and Military-Grade confidentiality.
 */
object CryptoManager {
    private var isProviderInitialized = false

    private fun ensureProvider() {
        if (!isProviderInitialized) {
            try {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.addProvider(BouncyCastleProvider())
                isProviderInitialized = true
            } catch (e: Exception) {
                android.util.Log.e("SecMess_Crypto", "BouncyCastle init failed, falling back to system")
            }
        }
    }

    private const val AES_GCM_TAG_LENGTH = 128 // 16 bytes authentication tag
    private const val IV_LENGTH = 12 // Standard 96-bit IV for GCM

    /**
     * Generates a new X25519 KeyPair for Curve25519 Diffie-Hellman.
     */
    fun generateX25519KeyPair(): KeyPair {
        ensureProvider()
        val kpg = KeyPairGenerator.getInstance("X25519", BouncyCastleProvider.PROVIDER_NAME)
        return kpg.generateKeyPair()
    }

    /**
     * Derives a 32-byte shared secret from a Private Key and a Peer's Public Key.
     * Uses XDH (X25519) for the handshake.
     */
    fun deriveSharedSecret(privateKey: PrivateKey, publicKeyBytes: ByteArray): ByteArray {
        ensureProvider()
        val kf = KeyFactory.getInstance("X25519", BouncyCastleProvider.PROVIDER_NAME)
        val publicKey = kf.generatePublic(X509EncodedKeySpec(publicKeyBytes))
        
        val ka = KeyAgreement.getInstance("X25519", BouncyCastleProvider.PROVIDER_NAME)
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }

    /**
     * Encrypts data using AES-256-GCM with the derived shared secret.
     * @return Base64 string containing [IV + Ciphertext + Tag]
     */
    fun encrypt(data: ByteArray, sharedSecret: ByteArray): String {
        ensureProvider()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME)
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv) // Fresh IV for every encryption
        
        // AES-256 requires a 32-byte key
        val keySpec = SecretKeySpec(sharedSecret, 0, 32, "AES")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH, iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val ciphertext = cipher.doFinal(data)
        
        val combined = iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts ciphertext using AES-256-GCM.
     * @param encryptedBase64 Combined IV and Ciphertext
     * @param sharedSecret The 32-byte derived secret
     */
    fun decrypt(encryptedBase64: String, sharedSecret: ByteArray): ByteArray {
        ensureProvider()
        val combined = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val iv = combined.sliceArray(0 until IV_LENGTH)
        val ciphertext = combined.sliceArray(IV_LENGTH until combined.size)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME)
        val keySpec = SecretKeySpec(sharedSecret, 0, 32, "AES")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH, iv)
        
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(ciphertext)
    }
}
