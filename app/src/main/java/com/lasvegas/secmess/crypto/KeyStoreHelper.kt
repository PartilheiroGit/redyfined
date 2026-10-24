@file:Suppress("DEPRECATION")

package com.lasvegas.secmess.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec

enum class UserTier {
    MASTER,     // Tier 0
    REFERRER,   // Tier 1
    FIELD_USER  // Tier 2
}

class KeyStoreHelper(context: Context) {
    companion object {
        // Revoked/locked devices observe this to fall back to the calendar immediately.
        val adminRevokedState = MutableStateFlow(false)
    }

    // Ultimate fallback for Android 16 stability
    private val fallbackPrefs = context.getSharedPreferences("research_keys_standard", Context.MODE_PRIVATE)

    private val masterKey by lazy {
        try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: Exception) {
            null
        }
    }

    private val sharedPrefs by lazy {
        val mk = masterKey
        if (mk != null) {
            try {
                EncryptedSharedPreferences.create(
                    context,
                    "secure_keys",
                    mk,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // Rescue logic: Clear corrupted secure keys and fallback to re-init
                try {
                    context.getSharedPreferences("secure_keys", Context.MODE_PRIVATE).edit().clear().apply()
                    EncryptedSharedPreferences.create(
                        context,
                        "secure_keys",
                        mk,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (_: Exception) {
                    fallbackPrefs
                }
            }
        } else {
            fallbackPrefs
        }
    }

    // --- Identity and Tiers ---

    fun getUserId(): String {
        if (isAdmin()) return "admin_001"

        // A identidade é estável: promover a sub-admin muda o papel, não o uid.
        // Sem isto, `becomeSubAdmin()` mudava o prefixo e o device perdia a aprovação.
        sharedPrefs.getString("stable_user_id", null)?.takeIf { it.isNotBlank() }?.let { return it }

        val pubKey = getPublicKey() ?: return "user_pending"
        val keyHash = Base64.encodeToString(pubKey, Base64.NO_WRAP).takeLast(8)
        val tierName = sharedPrefs.getString("user_tier", UserTier.FIELD_USER.name)
        val id = if (tierName == UserTier.REFERRER.name) "referrer_$keyHash" else "user_$keyHash"
        sharedPrefs.edit().putString("stable_user_id", id).apply()
        return id
    }

    fun getTier(): UserTier {
        if (isAdmin()) return UserTier.MASTER

        val role = sharedPrefs.getString("user_tier", UserTier.FIELD_USER.name)
        return try { UserTier.valueOf(role!!) } catch (e: Exception) { UserTier.FIELD_USER }
    }

    fun setTier(tier: UserTier) {
        sharedPrefs.edit().putString("user_tier", tier.name).apply()
    }

    // --- Status Logic ---
    fun isInitiallyUnlocked(): Boolean = sharedPrefs.getBoolean("is_unlocked_session", false)
    fun setInitiallyUnlocked(unlocked: Boolean) = sharedPrefs.edit().putBoolean("is_unlocked_session", unlocked).apply()

    // --- Crypto Storage ---

    fun storePrivateKey(key: PrivateKey) {
        val encoded = Base64.encodeToString(key.encoded, Base64.DEFAULT)
        sharedPrefs.edit().putString("private_key", encoded).apply()
    }

    fun getPrivateKey(): PrivateKey? {
        P2PCrypto.ensureProvider()
        val encoded = sharedPrefs.getString("private_key", null) ?: return null
        val keyBytes = Base64.decode(encoded, Base64.DEFAULT)
        val kf = KeyFactory.getInstance("X25519", "BC")
        return kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    }

    fun storePublicKey(publicKeyBytes: ByteArray) {
        val encoded = Base64.encodeToString(publicKeyBytes, Base64.DEFAULT)
        sharedPrefs.edit().putString("public_key", encoded).apply()
    }

    fun getPublicKey(): ByteArray? {
        val encoded = sharedPrefs.getString("public_key", null) ?: return null
        return Base64.decode(encoded, Base64.DEFAULT)
    }

    // --- P2P (111): chaves do onion service e da identidade de assinatura ---

    /**
     * Chave privada ED25519-V3 do onion service, em base64. Persistente: o endereço
     * `.onion` do device é estável entre arranques e reboots.
     */
    /** Chave local do chat (32 B, por device). Substitui a chave estática no APK. */
    fun getOrCreateLocalKey(): ByteArray {
        val stored = sharedPrefs.getString("local_display_key", null)
        if (!stored.isNullOrBlank()) {
            try {
                val k = Base64.decode(stored, Base64.NO_WRAP)
                if (k.size == 32) return k
            } catch (_: Exception) {}
        }
        val k = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        sharedPrefs.edit().putString("local_display_key", Base64.encodeToString(k, Base64.NO_WRAP)).apply()
        return k
    }

    fun getOrCreateOnionKeyB64(): String {
        val stored = sharedPrefs.getString("p2p_onion_key", null)
        if (!stored.isNullOrBlank()) return stored
        val key = io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3.PrivateKey
            .generate()
            .base64()
        sharedPrefs.edit().putString("p2p_onion_key", key).apply()
        return key
    }

    /** Par Ed25519 de assinatura (envelopes P2P): seed + chave pública cruas (32 B). */
    fun getOrCreateSigningKey(): P2PCrypto.SigningKeyPair {
        val seedB64 = sharedPrefs.getString("p2p_signing_seed", null)
        val pubB64 = sharedPrefs.getString("p2p_signing_public", null)
        if (!seedB64.isNullOrBlank() && !pubB64.isNullOrBlank()) {
            try {
                val seed = Base64.decode(seedB64, Base64.NO_WRAP)
                val pub = Base64.decode(pubB64, Base64.NO_WRAP)
                if (seed.size == 32 && pub.size == 32) return P2PCrypto.SigningKeyPair(seed, pub)
            } catch (_: Exception) {
                // chave ilegível (restauro/limpeza) -> gera outra; os pares voltam a fazer TOFU
            }
        }
        // Migração de builds anteriores: seed dentro do PKCS8 + pública DER.
        val oldPriv = sharedPrefs.getString("p2p_signing_private", null)
        val oldPub = sharedPrefs.getString("p2p_signing_public", null)
        if (!oldPriv.isNullOrBlank() && !oldPub.isNullOrBlank()) {
            try {
                val seed = P2PCrypto.seedFromOldPrivate(Base64.decode(oldPriv, Base64.NO_WRAP))
                val pub = P2PCrypto.rawPublic(Base64.decode(oldPub, Base64.NO_WRAP))
                if (seed != null && seed.size == 32 && pub.size == 32) {
                    val kp = P2PCrypto.SigningKeyPair(seed, pub)
                    sharedPrefs.edit()
                        .putString("p2p_signing_seed", Base64.encodeToString(seed, Base64.NO_WRAP))
                        .putString("p2p_signing_public", Base64.encodeToString(pub, Base64.NO_WRAP))
                        .apply()
                    android.util.Log.i("OGP2P", "chave Ed25519 migrada do build anterior")
                    return kp
                }
            } catch (_: Exception) {}
        }
        val kp = P2PCrypto.generateSigningKeyPair()
        sharedPrefs.edit()
            .putString("p2p_signing_seed", Base64.encodeToString(kp.seed, Base64.NO_WRAP))
            .putString("p2p_signing_public", Base64.encodeToString(kp.publicKey, Base64.NO_WRAP))
            .apply()
        return kp
    }

    fun signingPublicB64(): String = Base64.encodeToString(
        P2PCrypto.publicToDer(getOrCreateSigningKey().publicKey), Base64.NO_WRAP)

    fun signP2P(data: ByteArray): String = P2PCrypto.sign(data, getOrCreateSigningKey().seed)

    fun storePin(pin: String) {
        sharedPrefs.edit().putString("app_pin", pin).apply()
    }

    fun verifyPin(pin: String): Boolean {
        return sharedPrefs.getString("app_pin", null) == pin
    }

    fun clearPin() {
        sharedPrefs.edit().remove("app_pin").apply()
    }
    
    fun hasPin(): Boolean = sharedPrefs.contains("app_pin")

    // --- Deleted account -> Calendar channel (131) ---
    // Set when the relay removes this uid from the roster. The app must update to the calendar
    // APK on the next PIN entry; it never returns to the secure hub.
    fun setDeletedChannel(versionUrl: String, apkUrl: String) {
        sharedPrefs.edit()
            .putBoolean("deleted_account", true)
            .putString("agenda_version_url", versionUrl)
            .putString("agenda_apk_url", apkUrl)
            .apply()
    }
    fun isDeletedAccount(): Boolean = sharedPrefs.getBoolean("deleted_account", false)
    fun getAgendaVersionUrl(): String? = sharedPrefs.getString("agenda_version_url", null)
    fun getAgendaApkUrl(): String? = sharedPrefs.getString("agenda_apk_url", null)

    // --- Permissions and Network ---

    fun setAccessEnabled(enabled: Boolean) = sharedPrefs.edit().putBoolean("access_enabled", enabled).apply()
    fun isAccessEnabled(): Boolean = sharedPrefs.getBoolean("access_enabled", true)

    fun setBanned(banned: Boolean) {
        sharedPrefs.edit().putBoolean("is_banned", banned).apply()
        if (banned) {
            sharedPrefs.edit().clear().putBoolean("is_banned", true).apply()
        }
    }
    fun isBanned(): Boolean = sharedPrefs.getBoolean("is_banned", false)
    
    fun setInviterId(inviterId: String) = sharedPrefs.edit().putString("inviter_id", inviterId).apply()
    fun getInviterId(): String? = sharedPrefs.getString("inviter_id", null)

    fun setRelayUrl(url: String) = sharedPrefs.edit().putString("custom_relay_url", url).apply()
    fun getRelayUrl(): String? = sharedPrefs.getString("custom_relay_url", null)

    // --- UI and Visuals ---

    // Local override (this device's private name for a contact)
    fun setLocalDisplayName(userId: String, name: String) = sharedPrefs.edit().putString("alias_$userId", name).apply()
    fun getLocalAlias(userId: String): String? = sharedPrefs.getString("alias_$userId", null)?.ifBlank { null }

    // Suggested name = the nickname a contact broadcast about themselves (device-local cache).
    fun setSuggestedName(userId: String, name: String) = sharedPrefs.edit().putString("suggested_$userId", name).apply()
    fun getSuggestedName(userId: String): String? = sharedPrefs.getString("suggested_$userId", null)?.ifBlank { null }

    // This device's own nickname (chosen on first run, shared in presence).
    fun getMyNickname(): String = sharedPrefs.getString("my_nickname", "") ?: ""
    fun setMyNickname(name: String) = sharedPrefs.edit().putString("my_nickname", name.trim()).apply()
    fun hasMyNickname(): Boolean = getMyNickname().isNotBlank()

    // Resolution order: my local override -> contact's suggested name -> neutral placeholder.
    // Never exposes the raw network id in the UI.
    fun getLocalDisplayName(userId: String): String =
        getLocalAlias(userId) ?: getSuggestedName(userId) ?: "Unnamed device"

    fun setLanguage(language: String) = sharedPrefs.edit().putString("app_language", language).apply()
    fun getLanguage(): String = sharedPrefs.getString("app_language", "en") ?: "en"

    fun setTheme(theme: String) = sharedPrefs.edit().putString("app_theme", theme).apply()
    fun getTheme(): String = sharedPrefs.getString("app_theme", "Follow System") ?: "Follow System"

    fun hasCompletedIntro(majorVersion: Int): Boolean = sharedPrefs.getBoolean("major_intro_completed_$majorVersion", false)
    fun setIntroCompleted(majorVersion: Int) = sharedPrefs.edit().putBoolean("major_intro_completed_$majorVersion", true).apply()

    // --- Shake Code ---
    fun setShakeEnabled(enabled: Boolean) = sharedPrefs.edit().putBoolean("shake_enabled", enabled).apply()
    fun isShakeEnabled(): Boolean = sharedPrefs.getBoolean("shake_enabled", false)
    fun storeShakeCode(intervals: List<Long>) = sharedPrefs.edit().putString("shake_code", intervals.joinToString(",")).apply()
    fun getShakeCode(): List<Long> = sharedPrefs.getString("shake_code", null)?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
    fun hasShakeCode(): Boolean = sharedPrefs.contains("shake_code")

    // --- Stealth Mode (Unsafe Zones) ---
    fun setStealthEndTime(timestamp: Long) = sharedPrefs.edit().putLong("stealth_end_time", timestamp).apply()
    fun getStealthEndTime(): Long = sharedPrefs.getLong("stealth_end_time", 0L)
    fun isStealthActive(): Boolean = System.currentTimeMillis() < getStealthEndTime()

    fun isAdmin(): Boolean = getAdminSession().isNotEmpty() && !isAdminRevoked()
    fun getAdminSession(): String = sharedPrefs.getString("admin_session", "") ?: ""
    fun becomeAdmin(session: String) {
        sharedPrefs.edit().putString("admin_session", session).putBoolean("admin_revoked", false).apply()
        adminRevokedState.value = false
    }
    fun clearAdminSession() = sharedPrefs.edit().remove("admin_session").apply()

    // Admin transfer: the server issues the session; a revoke clears it and locks this phone.
    fun setAdminRevoked(revoked: Boolean) {
        sharedPrefs.edit().putBoolean("admin_revoked", revoked).apply()
        if (revoked) sharedPrefs.edit().remove("admin_session").apply()
        adminRevokedState.value = revoked
    }
    fun isAdminRevoked(): Boolean = sharedPrefs.getBoolean("admin_revoked", false)
    fun becomeSubAdmin() = setTier(UserTier.REFERRER)
    // unique per-device id (pubkey tail) used to distinguish a new admin from the old during transfer
    fun deviceId(): String { val pk = getPublicKey() ?: return "dev_none"; return Base64.encodeToString(pk, Base64.NO_WRAP).takeLast(12) }

    // A scanned admin QR survives flaky Tor: it is retried on every relay connect until the
    // server grants/denies it. The server token itself is single-use and expires in 10 minutes.
    fun setPendingAdminClaim(token: String) {
        sharedPrefs.edit().putString("pending_admin_claim", token)
            .putLong("pending_admin_claim_at", System.currentTimeMillis()).apply()
    }
    fun getPendingAdminClaim(): String = sharedPrefs.getString("pending_admin_claim", "") ?: ""
    fun getPendingAdminClaimAt(): Long = sharedPrefs.getLong("pending_admin_claim_at", 0L)
    fun clearPendingAdminClaim() = sharedPrefs.edit()
        .remove("pending_admin_claim").remove("pending_admin_claim_at").apply()
    
    fun wipeNetworkConfig() {
        sharedPrefs.edit().remove("custom_relay_url").apply()
    }

    // --- Blocked users (local; keyed on the fixed network id, never the display name) ---
    // Blocking is mutual-invisibility: the relay is told so the blocked peer can't see you either.
    fun getBlockedUsers(): Set<String> = sharedPrefs.getStringSet("blocked_users", emptySet())?.toSet() ?: emptySet()
    fun isUserBlocked(id: String): Boolean = getBlockedUsers().contains(id)
    fun blockUser(id: String) {
        val s = getBlockedUsers().toMutableSet(); s.add(id)
        sharedPrefs.edit().putStringSet("blocked_users", s).apply()
    }
    fun unblockUser(id: String) {
        val s = getBlockedUsers().toMutableSet(); s.remove(id)
        sharedPrefs.edit().putStringSet("blocked_users", s).apply()
    }

    // Peers who blocked ME (learned via an encrypted peer directive): I hide them too, so they
    // can't see me. Stored locally + encrypted; the relay never knows about any of this.
    fun getHiddenByUsers(): Set<String> = sharedPrefs.getStringSet("hidden_by_users", emptySet())?.toSet() ?: emptySet()
    fun isHiddenBy(id: String): Boolean = getHiddenByUsers().contains(id)
    fun addHiddenBy(id: String) {
        val s = getHiddenByUsers().toMutableSet(); s.add(id)
        sharedPrefs.edit().putStringSet("hidden_by_users", s).apply()
    }
    fun removeHiddenBy(id: String) {
        val s = getHiddenByUsers().toMutableSet(); s.remove(id)
        sharedPrefs.edit().putStringSet("hidden_by_users", s).apply()
    }

    // --- Unsafe zones (server-published GeoJSON, cached for offline) ---
    fun getUnsafeGeoJson(): String = sharedPrefs.getString("unsafe_geojson", "") ?: ""
    fun setUnsafeGeoJson(geoJson: String) = sharedPrefs.edit().putString("unsafe_geojson", geoJson).apply()
}
