package com.lasvegas.secmess.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Descritores P2P recebidos do servidor (plano de controlo).
 *
 * O servidor só distribui: uid -> { onion, chave X25519, chave de assinatura Ed25519 }.
 * O device faz TOFU: a primeira chave vista para um uid fica fixada; se um descritor
 * posterior tentar trocar as chaves, é rejeitado (possível servidor comprometido/MITM).
 */
@Serializable
data class PeerDescriptor(
    val uid: String,
    val onion: String,
    val x25519: String,
    val signing: String,
    val updatedAt: Long,
)

object PeerDirectory {
    private const val TAG = "OGP2P"
    private const val PREFS = "og_p2p_peers"
    private const val KEY = "descriptors"

    private val json = Json { ignoreUnknownKeys = true }
    private val peers = java.util.concurrent.ConcurrentHashMap<String, PeerDescriptor>()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(KEY, null) ?: return
        try {
            val list = json.decodeFromString(ListSerializer(PeerDescriptor.serializer()), raw)
            list.forEach { peers[it.uid] = it }
            Log.i(TAG, "diretoria: ${list.size} descritor(es) carregados")
        } catch (t: Throwable) {
            Log.w(TAG, "diretoria ilegível: ${t.message}")
        }
    }

    fun get(uid: String): PeerDescriptor? = peers[uid]

    fun all(): List<PeerDescriptor> = peers.values.toList()

    /**
     * Aceita um descritor novo ou atualiza o existente se as chaves não mudarem.
     * @return true se ficou utilizável.
     */
    @Synchronized
    fun upsert(uid: String, onion: String, x25519: String, signing: String): Boolean {
        if (uid.isBlank() || onion.isBlank() || x25519.isBlank() || signing.isBlank()) return false
        // O Tor devolve o endereço sem ".onion"; normalizar dos dois lados.
        val host = if (onion.endsWith(".onion")) onion else "$onion.onion"
        if (host.length < 60) return false
        val existing = peers[uid]
        if (existing != null) {
            val sameKey = try {
                com.lasvegas.secmess.crypto.P2PCrypto.rawPublic(
                    android.util.Base64.decode(existing.signing, android.util.Base64.NO_WRAP)
                ).contentEquals(
                    com.lasvegas.secmess.crypto.P2PCrypto.rawPublic(
                        android.util.Base64.decode(signing, android.util.Base64.NO_WRAP)
                    )
                )
            } catch (_: Exception) { false }
            // A âncora é o par onion + X25519 (identidade). A chave de assinatura pode
            // rodar (ex.: migração de build) sem mudar a identidade; aceitamos com aviso.
            if (existing.x25519 != x25519 || existing.onion != host) {
                Log.w(TAG, "TOFU: identidade de $uid mudou (onion/x25519) — rejeitado")
                return false
            }
            if (existing.signing != signing) {
                Log.w(TAG, "rotação da chave de assinatura de $uid — aceite")
                peers[uid] = existing.copy(signing = signing, updatedAt = System.currentTimeMillis())
                persist()
            }
            return true
        }
        val d = PeerDescriptor(uid, host, x25519, signing, System.currentTimeMillis())
        peers[uid] = d
        persist()
        Log.i(TAG, "descritor novo: $uid -> $host")
        return true
    }

    /**
     * Fixa a identidade vinda de um QR de par (client-auth v3). A âncora continua a ser
     * onion + X25519; uma assinatura diferente (rotação de build) é aceite.
     * @return false se já existe um descritor com onion/X25519 diferentes (conflito).
     */
    @Synchronized
    fun pin(uid: String, onion: String, x25519: String, signing: String): Boolean {
        if (uid.isBlank() || x25519.isBlank() || signing.isBlank()) return false
        val host = QrPairing.normalizeOnion(onion) ?: return false
        val existing = peers[uid]
        if (existing != null) {
            if (existing.onion != host || existing.x25519 != x25519) {
                Log.w(TAG, "PIN: identidade de $uid diverge do QR — rejeitado")
                return false
            }
            if (existing.signing != signing) {
                peers[uid] = existing.copy(signing = signing, updatedAt = System.currentTimeMillis())
                persist()
            }
            return true
        }
        peers[uid] = PeerDescriptor(uid, host, x25519, signing, System.currentTimeMillis())
        persist()
        Log.i(TAG, "identidade fixada por QR: $uid -> $host")
        return true
    }

    fun forget(uid: String) {
        if (peers.remove(uid) != null) persist()
    }

    private fun persist() {
        val p = prefs ?: return
        try {
            val raw = json.encodeToString(ListSerializer(PeerDescriptor.serializer()), peers.values.toList())
            p.edit().putString(KEY, raw).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "não gravou a diretoria: ${t.message}")
        }
    }
}
