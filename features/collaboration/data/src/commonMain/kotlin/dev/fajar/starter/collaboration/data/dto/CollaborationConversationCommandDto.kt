package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
internal class CollaborationConversationCommandDto(
    val id: String,
    val audience: String,
    val accountId: String,
    val companyId: String,
    val nativeSessionId: String,
    val createdAt: String,
    val action: String,
    val peerAccountId: String? = null,
    val conversationId: String? = null,
    val deviceId: String? = null,
    val deviceVersion: Long? = null,
    val provisioningVersion: Long? = null,
) {
    override fun toString() = "CollaborationConversationCommandDto([redacted])"
}

@Serializable
internal class CollaborationConversationOutcomeDto(
    val phase: String,
    val attempts: Int,
    val failure: CollaborationSetupFailureDto? = null,
    val receipt: CollaborationReceiptDto? = null,
) {
    override fun toString() = "CollaborationConversationOutcomeDto([redacted])"
}
