package dev.fajar.starter.identity.data.push.dto

import kotlinx.serialization.Serializable

@Serializable
data class NativePushOwnerDto(
    val localSessionId: String,
    val audience: String,
    val accountId: String,
    val familyId: String,
    val expiresAt: String,
)

@Serializable
data class NativePushCommandDto(
    val operationId: String,
    val expectedVersion: Long?,
    val kind: String,
    val platform: String? = null,
    val token: String? = null,
) {
    override fun toString() = "NativePushCommandDto([redacted])"
}

@Serializable
data class NativePushStateDto(
    val format: Int = 1,
    val desired: Boolean,
    val owner: NativePushOwnerDto?,
    val token: String?,
    val registration: NativePushRegistrationDto?,
    val pending: NativePushCommandDto?,
    val retiringToken: Boolean,
    val revocations: List<NativePushOwnerDto>,
    val enableRequested: Boolean = false,
) {
    override fun toString() = "NativePushStateDto([redacted])"
}
