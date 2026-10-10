package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class AuthResponse(val account: AccountDto, val credentials: NativeCredentialsDto) {
    override fun toString() = "AuthResponse([redacted])"
}
