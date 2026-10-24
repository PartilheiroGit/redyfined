package com.lasvegas.secmess.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Estado de um par (client-auth v3). */
enum class PairState(val wire: String) {
    /** QR emitido, à espera que o outro device o leia. */
    PENDING("pending"),

    /** Chave provada nos dois sentidos (hello com HMAC). */
    VERIFIED("verified");

    companion object {
        fun from(value: String?): PairState? = values().firstOrNull { it.wire == value }
    }
}

@Serializable
data class PairKey(
    val uid: String,
    val psk: String,
    val state: String = PairState.PENDING.wire,
    val updatedAt: Long = 0L,
)

/**
 * Chaves de par do client-auth v3 (build 127).
 *
 * Uma PSK de 32 B por peer, criada no device que emite o QR e entregue fora do servidor.
 * Nunca é publicada no relay, no descritor ou em `og_p2p_desc.json`.
 *
 * Persistência em `EncryptedSharedPreferences`. Se o Keystore não estiver disponível, os pares
 * ficam **só em memória** (nunca em prefs em claro).
 */
object PairKeyStore {
    private const val TAG = "OGPAIR"
    private const val PREFS = "og_pair_keys"
    private const val KEY = "pairs"
    private const val PSK_BYTES = 32

    private val json = Json { ignoreUnknownKeys = true }
    private val pairs = ConcurrentHashMap<String, PairKey>()
    private var prefs: SharedPreferences? = null
    private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "armazenamento cifrado indisponível (${t.message}); pares só em memória")
            prefs = null
        }
        load()
    }

    fun newPsk(): ByteArray = ByteArray(PSK_BYTES).also { SecureRandom().nextBytes(it) }

    fun psk(uid: String): ByteArray? {
        val value = pairs[uid]?.psk ?: return null
        return try {
            Base64.decode(value, Base64.NO_WRAP).takeIf { it.size == PSK_BYTES }
        } catch (_: Throwable) {
            null
        }
    }

    fun state(uid: String): PairState? = PairState.from(pairs[uid]?.state)

    fun isVerified(uid: String): Boolean = state(uid) == PairState.VERIFIED

    fun hasKey(uid: String): Boolean = pairs.containsKey(uid)

    fun all(): List<PairKey> = pairs.values.sortedBy { it.uid }

    /** Emissor do QR: guarda a PSK à espera de prova do outro lado. */
    @Synchronized
    fun putPending(uid: String, psk: ByteArray): Boolean = put(uid, psk, PairState.PENDING)

    /** Quem leu o QR: a identidade foi fixada no scan, o par entra verificado. */
    @Synchronized
    fun putVerified(uid: String, psk: ByteArray): Boolean = put(uid, psk, PairState.VERIFIED)

    @Synchronized
    fun markVerified(uid: String) {
        val current = pairs[uid] ?: return
        if (current.state == PairState.VERIFIED.wire) return
        pairs[uid] = current.copy(state = PairState.VERIFIED.wire, updatedAt = System.currentTimeMillis())
        persist()
        Log.i(TAG, "par verificado: $uid")
    }

    @Synchronized
    fun remove(uid: String) {
        if (pairs.remove(uid) != null) {
            persist()
            Log.i(TAG, "par removido: $uid")
        }
    }

    @Synchronized
    private fun put(uid: String, psk: ByteArray, state: PairState): Boolean {
        if (uid.isBlank() || psk.size != PSK_BYTES) return false
        pairs[uid] = PairKey(
            uid = uid,
            psk = Base64.encodeToString(psk, Base64.NO_WRAP),
            state = state.wire,
            updatedAt = System.currentTimeMillis(),
        )
        persist()
        Log.i(TAG, "par ${state.wire}: $uid")
        return true
    }

    private fun load() {
        val raw = prefs?.getString(KEY, null) ?: return
        try {
            json.decodeFromString(ListSerializer(PairKey.serializer()), raw).forEach {
                pairs[it.uid] = it
            }
            Log.i(TAG, "pares carregados: ${pairs.size}")
        } catch (t: Throwable) {
            Log.w(TAG, "pares ilegíveis: ${t.message}")
        }
    }

    private fun persist() {
        val p = prefs ?: return
        try {
            val raw = json.encodeToString(
                ListSerializer(PairKey.serializer()),
                pairs.values.toList(),
            )
            p.edit().putString(KEY, raw).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "não gravou os pares: ${t.message}")
        }
    }
}
