@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.data.mappers

import dev.fajar.starter.identity.data.dto.*
import dev.fajar.starter.identity.domain.entities.*
import io.ktor.http.Url
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun MfaEnrollmentDto.toEnrollment(): MfaEnrollment {
    Uuid.parse(operationId)
    require(secret.matches(Regex("[A-Z2-7]{32}")) && otpauthUri.length <= 2000)
    val uri = Url(otpauthUri)
    require(
        uri.protocol.name == "otpauth" && uri.host == "totp" && uri.parameters["secret"] == secret
    )
    return MfaEnrollment(
        operationId,
        secret,
        otpauthUri,
        Instant.parse(expiresAt).toEpochMilliseconds(),
    )
}

fun MfaConfirmationDto.toConfirmation(user: User, audience: String): MfaConfirmation {
    require(recoveryCodes.size == 10 && recoveryCodes.distinct().size == 10)
    require(
        recoveryCodes.all {
            it.length in 1..64 && it.all { char -> char.isLetterOrDigit() || char == '-' }
        }
    )
    return MfaConfirmation(credentials.toAuthenticatedUser(user, audience), recoveryCodes.toList())
}
