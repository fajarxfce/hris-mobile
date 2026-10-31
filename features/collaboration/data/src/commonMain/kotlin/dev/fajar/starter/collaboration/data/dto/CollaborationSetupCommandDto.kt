package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
internal class CollaborationSetupCommandDto(
    val id: String,
    val audience: String,
    val accountId: String,
    val companyId: String,
    val nativeSessionId: String,
    val platform: String,
    val clientBuild: Long,
    val createdAt: String,
    val action: String,
    val label: String? = null,
    val deviceId: String? = null,
    val deviceVersion: Long? = null,
    val provisioningVersion: Long? = null,
    val gatewayVersion: Long? = null,
) {
    override fun toString() = "CollaborationSetupCommandDto([redacted])"
}

@Serializable
internal class CollaborationSetupOutcomeDto(
    val phase: String,
    val attempts: Int,
    val failure: CollaborationSetupFailureDto? = null,
    val receipt: CollaborationReceiptDto? = null,
    val gateway: CollaborationGatewayDto? = null,
) {
    override fun toString() = "CollaborationSetupOutcomeDto([redacted])"
}

@Serializable
internal data class CollaborationSetupFailureDto(
    val kind: String,
    val code: String?,
    val retryAfterSeconds: Long?,
)
