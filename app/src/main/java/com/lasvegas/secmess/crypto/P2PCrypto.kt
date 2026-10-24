package com.lasvegas.secmess.crypto

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Criptografia do transporte P2P (fase 111).
 *
 * - Identidade de assinatura: Ed25519 (chave fixa, gerada no device).
 * - Troca de chaves: X25519 **efémero por mensagem** contra a chave estática do destinatário.
 * - KDF: HKDF-SHA256.
 * - Cifra: AES-256-GCM (IV de 12 bytes por mensagem).
 * - Autenticidade: assinatura Ed25519 do sender sobre o envelope (sem o campo `sig`).
 *
 * O servidor nunca vê nenhuma destas chaves em uso: só distribui a chave pública de
 * identidade no descritor (TOFU no device).
 */
object P2PCrypto {
    const val PROVIDER = "BC"
    private const val GCM_TAG_BITS = 128
    const val IV_LEN = 12
    private const val VERSION = "OG-P2P-v1"

    @Volatile private var providerReady = false

    /**
     * O Android traz um provider "BC" **capado** (sem X25519). Se já existir, é preciso
     * removê-lo e registar o BouncyCastle completo — senão KeyFactory("X25519","BC") falha
     * com "no such algorithm" (aconteceu no A15).
     */
    fun ensureProvider() {
        if (providerReady) return
        synchronized(this) {
            if (providerReady) return
            try {
                Security.removeProvider(PROVIDER)
                Security.addProvider(BouncyCastleProvider())
            } catch (_: Throwable) {}
            providerReady = true
        }
    }

    // ---- chaves de identidade (Ed25519 via API lightweight do BouncyCastle) ----
    // O provider JCA "BC" do Android não expõe Ed25519; a API lightweight funciona
    // em qualquer API level e usa chaves cruas de 32 bytes.

    data class SigningKeyPair(val seed: ByteArray, val publicKey: ByteArray)

    fun generateSigningKeyPair(): SigningKeyPair {
        val priv = Ed25519PrivateKeyParameters(java.security.SecureRandom())
        return SigningKeyPair(priv.encoded, priv.generatePublicKey().encoded)
    }

    // Compatibilidade com builds anteriores (JCA/BC): a chave pública viaja em DER
    // (SubjectPublicKeyInfo); aceitamos DER ou cru (32 B) na verificação.
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )
    private val ED25519_PKCS8_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b,
        0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
    )

    fun publicToDer(raw: ByteArray): ByteArray = ED25519_SPKI_PREFIX + raw

    /** Normaliza uma chave pública codificada (DER 44 B ou cru 32 B) para 32 B. */
    fun rawPublic(encoded: ByteArray): ByteArray = when {
        encoded.size == 44 && encoded.copyOfRange(0, 12).contentEquals(ED25519_SPKI_PREFIX) ->
            encoded.copyOfRange(12, 44)
        encoded.size == 32 -> encoded
        else -> encoded
    }

    /** Extrai a seed de uma chave privada PKCS8 antiga (48 B) ou crua (32 B). */
    fun seedFromOldPrivate(encoded: ByteArray): ByteArray? = when {
        encoded.size == 48 && encoded.copyOfRange(0, 16).contentEquals(ED25519_PKCS8_PREFIX) ->
            encoded.copyOfRange(16, 48)
        encoded.size == 32 -> encoded
        else -> null
    }

    fun sign(data: ByteArray, seed: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(data, 0, data.size)
        return Base64.encodeToString(signer.generateSignature(), Base64.NO_WRAP)
    }

    fun verify(data: ByteArray, signatureB64: String, publicB64: String): Boolean {
        return try {
            val signer = Ed25519Signer()
            val raw = rawPublic(Base64.decode(publicB64, Base64.NO_WRAP))
            signer.init(false, Ed25519PublicKeyParameters(raw, 0))
            signer.update(data, 0, data.size)
            signer.verifySignature(Base64.decode(signatureB64, Base64.NO_WRAP))
        } catch (_: Exception) {
            false
        }
    }

    // ---- client-auth v3 (PSK por par) ----
    // A PSK não substitui o X25519/AES: entra como salt do HKDF, para prender o ciphertext ao par.
    const val VERSION_V3 = "OG-P2P-v3"

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hmacB64(key: ByteArray, data: ByteArray): String =
        Base64.encodeToString(hmacSha256(key, data), Base64.NO_WRAP)

    fun verifyHmac(key: ByteArray, data: ByteArray, proofB64: String): Boolean {
        return try {
            val proof = Base64.decode(proofB64, Base64.NO_WRAP)
            MessageDigest.isEqual(hmacSha256(key, data), proof)
        } catch (_: Throwable) {
            false
        }
    }

    private fun saltFor(psk: ByteArray?): ByteArray =
        if (psk == null) VERSION.toByteArray() else VERSION_V3.toByteArray() + psk

    // ---- envelope ----

    /** Cifra [plaintext] para a chave estática X25519 [recipientPublicDerB64]. */
    fun encryptTo(
        recipientPublicDerB64: String,
        plaintext: ByteArray,
        psk: ByteArray? = null,
    ): Triple<String, String, String> {
        ensureProvider()
        val kpg = KeyPairGenerator.getInstance("X25519", PROVIDER)
        val eph = kpg.generateKeyPair()

        val recipient = KeyFactory.getInstance("X25519", PROVIDER)
            .generatePublic(X509EncodedKeySpec(Base64.decode(recipientPublicDerB64, Base64.NO_WRAP)))

        val ka = KeyAgreement.getInstance("X25519", PROVIDER)
        ka.init(eph.private)
        ka.doPhase(recipient, true)
        val shared = ka.generateSecret()

        val salt = saltFor(psk)
        val key = hkdf(shared, salt, ByteArray(0), 32)
        val iv = ByteArray(IV_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", PROVIDER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)

        return Triple(
            Base64.encodeToString(eph.public.encoded, Base64.NO_WRAP),
            Base64.encodeToString(iv, Base64.NO_WRAP),
            Base64.encodeToString(ct, Base64.NO_WRAP),
        )
    }

    /** Decifra um envelope com a chave estática X25519 [myPrivate]. */
    fun decryptFrom(
        myPrivate: PrivateKey,
        ephPublicDerB64: String,
        ivB64: String,
        ctB64: String,
        psk: ByteArray? = null,
    ): ByteArray {
        ensureProvider()
        val ephPub = KeyFactory.getInstance("X25519", PROVIDER)
            .generatePublic(X509EncodedKeySpec(Base64.decode(ephPublicDerB64, Base64.NO_WRAP)))
        val ka = KeyAgreement.getInstance("X25519", PROVIDER)
        ka.init(myPrivate)
        ka.doPhase(ephPub, true)
        val shared = ka.generateSecret()

        val key = hkdf(shared, saltFor(psk), ByteArray(0), 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", PROVIDER)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivB64, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP))
    }

    /** HKDF-SHA256 (extract + expand de um bloco, suficiente para 32 bytes). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01)
        return mac.doFinal().copyOf(length)
    }
}
