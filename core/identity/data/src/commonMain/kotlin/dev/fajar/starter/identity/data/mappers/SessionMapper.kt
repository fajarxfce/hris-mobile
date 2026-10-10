@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.data.mappers

import dev.fajar.starter.identity.data.dto.*
import dev.fajar.starter.identity.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun TokensDto.toTokens(): SessionTokens {
    require(accessToken.isNotBlank() && refreshToken.isNotBlank() && expiresAtEpochMillis > 0) {
        "Invalid token response."
    }
    return SessionTokens(accessToken, refreshToken, expiresAtEpochMillis)
}

fun AccountDto.toUser(): User {
    Uuid.parse(id)
    require(
        displayName.isNotBlank() &&
            displayName.length <= 200 &&
            email.isNotBlank() &&
            email.length <= 254
    )
    return User(id, displayName, email)
}

fun AuthResponse.toAuthenticatedUser(audience: String) =
    credentials.toAuthenticatedUser(account.toUser(), audience)

fun NativeCredentialsDto.toAuthenticatedUser(user: User, audience: String): AuthenticatedUser {
    Uuid.parse(sessionId)
    require(tokenType == "Bearer" && audience.isNotBlank())
    require(
        listOf(accessToken, refreshToken).all { token ->
            token.length in 1..4096 && token.none(Char::isWhitespace)
        }
    )
    val accessExpiry = Instant.parse(accessExpiresAt).toEpochMilliseconds()
    val sessionExpiry = Instant.parse(sessionExpiresAt).toEpochMilliseconds()
    require(accessExpiry > 0 && accessExpiry <= sessionExpiry)
    return AuthenticatedUser(
        user,
        SessionTokens(accessToken, refreshToken, accessExpiry),
        SessionBinding(sessionId, audience, sessionExpiry),
    )
}

fun SessionDto.toSession(): Session {
    require(id.isNotBlank()) { "Invalid session record." }
    pendingRefreshOperationId?.let { kotlin.uuid.Uuid.parse(it) }
    Uuid.parse(binding.serverId)
    require(binding.audience.isNotBlank() && binding.expiresAtEpochMillis > 0)
    return Session(
        id,
        user.toUser(),
        tokens.toTokens(),
        SessionBinding(binding.serverId, binding.audience, binding.expiresAtEpochMillis),
        pendingRefreshOperationId,
    )
}

fun Session.toDto() =
    SessionDto(
        id,
        UserDto(user.id, user.name, user.email),
        TokensDto(tokens.accessToken, tokens.refreshToken, tokens.expiresAtEpochMillis),
        SessionBindingDto(binding.serverId, binding.audience, binding.expiresAtEpochMillis),
        pendingRefreshOperationId,
    )
