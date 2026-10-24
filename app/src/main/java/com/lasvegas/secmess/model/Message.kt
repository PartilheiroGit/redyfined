package com.lasvegas.secmess.model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val ciphertext: String, // Base64
    val timestamp: Long,
    val ephemeral: Boolean = true,
    val type: String = "chat", // "chat", "presence", "command", "location", "request", "read_receipt", "report", "kill", "live_location"
    val readTimestamp: Long? = null,
    val delivered: Boolean = false,
    val deliveredAt: Long? = null, // quando o ack chegou (relógio dos 5 min do sender)
    val versionCode: Int = 0, // build que enviou (para o painel mostrar a versão por device)
    val nickname: String = "" // sender's chosen display name (suggested to peers; never stored on relay)
)

@Serializable
enum class ConnectivityPolicy {
    ADMIN_ONLY,
    BOTH,
    SUB_ADMIN_ONLY
}

@Serializable
data class Invitation(
    val inviterId: String,
    val publicKey: String, // Base64
    val relayUrl: String,
    val relayKey: String,
    val apkUrl: String,
    val isReferrer: Boolean = false,
    val policy: ConnectivityPolicy = ConnectivityPolicy.BOTH, // Default for direct admin invites
    val requiresApproval: Boolean = true,
    val appDownloadChannel: Boolean = true,
    val singleUse: Boolean = true,
    val invitationId: String = java.util.UUID.randomUUID().toString(),
    val locationSharingEnabled: Boolean = true,
    val canRequestInvitations: Boolean = false,
    val canKill: Boolean = false,
    val canReport: Boolean = true
)
