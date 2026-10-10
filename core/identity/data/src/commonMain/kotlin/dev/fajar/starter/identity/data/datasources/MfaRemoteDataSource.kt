package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.dto.*

interface MfaRemoteDataSource {
    suspend fun begin(accessToken: String, operationId: String): MfaEnrollmentDto

    suspend fun confirm(
        accessToken: String,
        operationId: String,
        request: MfaConfirmationRequest,
    ): MfaConfirmationDto

    suspend fun verify(
        accessToken: String,
        operationId: String,
        request: MfaVerificationRequest,
    ): MfaConfirmationDto
}
