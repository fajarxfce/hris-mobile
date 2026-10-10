package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class AuthResponse(val user: UserDto, val tokens: TokensDto) {
    override fun toString() = "AuthResponse([redacted])"
}
