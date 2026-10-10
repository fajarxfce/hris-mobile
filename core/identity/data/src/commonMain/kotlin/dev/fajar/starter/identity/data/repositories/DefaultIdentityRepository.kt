package dev.fajar.starter.identity.data.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.data.datasources.AuthRemoteDataSource
import dev.fajar.starter.identity.data.dto.*
import dev.fajar.starter.identity.data.mappers.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.IdentityRepository
import dev.fajar.starter.identity.domain.sso.entities.SsoProof
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class DefaultIdentityRepository(
    private val remote: AuthRemoteDataSource,
    private val server: IdentityServer,
) : IdentityRepository {
    // HRIS browser OIDC currently has no native handoff endpoint. No client claims are trusted.
    override suspend fun completeSso(proof: SsoProof): AppResult<AuthenticatedUser> =
        AppResult.Failed(
            Failure(
                FailureKind.Unavailable,
                "Single sign-on is unavailable.",
                code = "identity.sso_unavailable",
            )
        )

    override suspend fun signIn(
        email: String,
        password: String,
        deviceName: String,
        operationId: String,
    ) = safeApiCall {
        remote
            .signIn(SignInRequest(email, password, deviceName), operationId)
            .toAuthenticatedUser(server.audience)
    }

    override suspend fun refresh(session: Session, operationId: String) = safeApiCall {
        remote
            .refresh(RefreshRequest(session.tokens.refreshToken), operationId)
            .toAuthenticatedUser(session.user, server.audience)
    }
}
