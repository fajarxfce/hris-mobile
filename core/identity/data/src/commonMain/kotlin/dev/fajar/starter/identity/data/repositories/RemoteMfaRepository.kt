package dev.fajar.starter.identity.data.repositories

import dev.fajar.starter.identity.data.datasources.MfaRemoteDataSource
import dev.fajar.starter.identity.data.dto.*
import dev.fajar.starter.identity.data.mappers.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.MfaRepository
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteMfaRepository(
    private val remote: MfaRemoteDataSource,
    private val server: IdentityServer,
) : MfaRepository {
    override suspend fun begin(accessToken: String, operationId: String) = safeApiCall {
        remote.begin(accessToken, operationId).toEnrollment()
    }

    override suspend fun confirm(
        session: Session,
        operationId: String,
        enrollmentId: String,
        code: String,
    ) = safeApiCall {
        remote
            .confirm(
                session.tokens.accessToken,
                operationId,
                MfaConfirmationRequest(enrollmentId, code),
            )
            .toConfirmation(session.user, server.audience)
    }

    override suspend fun verify(
        session: Session,
        operationId: String,
        code: String,
        recovery: Boolean,
    ) = safeApiCall {
        remote
            .verify(session.tokens.accessToken, operationId, MfaVerificationRequest(code, recovery))
            .credentials
            .toAuthenticatedUser(session.user, server.audience)
    }
}
