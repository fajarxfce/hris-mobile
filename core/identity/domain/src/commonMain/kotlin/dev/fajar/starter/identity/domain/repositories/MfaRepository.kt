package dev.fajar.starter.identity.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.*

interface MfaRepository {
    suspend fun begin(accessToken: String, operationId: String): AppResult<MfaEnrollment>

    suspend fun confirm(
        session: Session,
        operationId: String,
        enrollmentId: String,
        code: String,
    ): AppResult<MfaConfirmation>

    suspend fun verify(
        session: Session,
        operationId: String,
        code: String,
        recovery: Boolean,
    ): AppResult<AuthenticatedUser>
}
