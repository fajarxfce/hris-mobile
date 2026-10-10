package dev.fajar.starter.identity.data.mappers

import dev.fajar.starter.identity.data.dto.*
import dev.fajar.starter.identity.domain.entities.*

fun TokensDto.toTokens(): SessionTokens {
    require(accessToken.isNotBlank() && refreshToken.isNotBlank() && expiresAtEpochMillis > 0) {
        "Invalid token response."
    }
    return SessionTokens(accessToken, refreshToken, expiresAtEpochMillis)
}

fun AuthResponse.toAuthenticatedUser() = AuthenticatedUser(user.toUser(), tokens.toTokens())

fun SessionDto.toSession(): Session {
    require(id.isNotBlank()) { "Invalid session record." }
    return Session(id, user.toUser(), tokens.toTokens())
}

fun Session.toDto() =
    SessionDto(
        id,
        UserDto(user.id, user.name, user.email),
        TokensDto(tokens.accessToken, tokens.refreshToken, tokens.expiresAtEpochMillis),
    )
