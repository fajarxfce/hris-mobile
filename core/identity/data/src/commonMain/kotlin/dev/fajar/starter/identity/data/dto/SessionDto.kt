package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class SessionDto(
    val id: String,
    val user: UserDto,
    val tokens: TokensDto,
    val pendingRefreshOperationId: String? = null,
) {
    override fun toString() = "SessionDto([redacted])"
}
