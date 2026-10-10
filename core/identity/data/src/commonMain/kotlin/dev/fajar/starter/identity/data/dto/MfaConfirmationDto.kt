package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class MfaConfirmationDto(
    val credentials: NativeCredentialsDto,
    val recoveryCodes: List<String> = emptyList(),
) {
    override fun toString() = "MfaConfirmationDto([redacted])"
}
