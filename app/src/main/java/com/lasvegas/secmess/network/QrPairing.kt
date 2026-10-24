package com.lasvegas.secmess.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * QR de emparelhamento do client-auth v3 (build 127).
 *
 * `OGPAIR:v3:<base64url(json)>` transporta a identidade de quem emite (onion + X25519 +
 * Ed25519) e a PSK do par. Só o device visado (`to`) o pode aceitar. Não passa pelo servidor.
 */
object QrPairing {
    const val PREFIX = "OGPAIR:v3:"
    const val VERSION = 3
    const val PSK_BYTES = 32
    const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000 // 24 h

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()
    private val b64Encoder = Base64.getEncoder().withoutPadding()
    private val b64Decoder = Base64.getDecoder()

    @Serializable
    data class Invite(
        val v: Int = VERSION,
        val from: String,
        val to: String,
        val onion: String,
        val x25519: String,
        val signing: String,
        val psk: String,
        val exp: Long,
    )

    sealed class Scan {
        data class Ok(val invite: Invite, val psk: ByteArray) : Scan()
        data class Error(val reason: String) : Scan()
    }

    /**
     * Constrói o payload do QR. [from] é quem emite, [to] é o uid do device que o deve ler.
     * Devolve `null` se faltar algum campo ou a PSK não tiver 32 B.
     */
    fun encode(
        from: String,
        to: String,
        onion: String,
        x25519: String,
        signing: String,
        psk: ByteArray,
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_TTL_MS,
    ): String? {
        if (from.isBlank() || to.isBlank()) return null
        if (x25519.isBlank() || signing.isBlank()) return null
        if (psk.size != PSK_BYTES) return null
        val host = normalizeOnion(onion) ?: return null
        val invite = Invite(
            from = from,
            to = to,
            onion = host,
            x25519 = x25519,
            signing = signing,
            psk = b64Encoder.encodeToString(psk),
            exp = now + ttlMs,
        )
        return PREFIX + urlEncoder.encodeToString(json.encodeToString(invite).toByteArray())
    }

    /** Lê um payload. [myUid] tem de ser o `to` do QR. */
    fun decode(
        payload: String,
        myUid: String,
        now: Long = System.currentTimeMillis(),
    ): Scan {
        val trimmed = payload.trim()
        if (!trimmed.startsWith(PREFIX)) return Scan.Error("not a pairing code")
        val raw = try {
            String(urlDecoder.decode(trimmed.removePrefix(PREFIX)))
        } catch (_: Throwable) {
            return Scan.Error("invalid payload")
        }
        val invite = try {
            json.decodeFromString<Invite>(raw)
        } catch (_: Throwable) {
            return Scan.Error("invalid payload")
        }
        if (invite.v != VERSION) return Scan.Error("unsupported version")
        if (invite.to != myUid) return Scan.Error("this code is for another device")
        if (invite.from.isBlank() || invite.x25519.isBlank() || invite.signing.isBlank()) {
            return Scan.Error("invalid fields")
        }
        if (invite.exp in 1..now) return Scan.Error("code expired")
        val host = normalizeOnion(invite.onion) ?: return Scan.Error("invalid onion")
        val psk = try {
            b64Decoder.decode(invite.psk)
        } catch (_: Throwable) {
            return Scan.Error("invalid key")
        }
        if (psk.size != PSK_BYTES) return Scan.Error("invalid key")
        return Scan.Ok(invite.copy(onion = host), psk)
    }

    /** Normaliza um endereço v3 para `<56 chars>.onion`; devolve `null` se não for válido. */
    fun normalizeOnion(onion: String): String? {
        var host = onion.trim().lowercase()
        if (host.endsWith(".onion")) host = host.removeSuffix(".onion")
        if (host.length != 56) return null
        if (!host.all { it in 'a'..'z' || it in '2'..'7' }) return null
        return "$host.onion"
    }
}
