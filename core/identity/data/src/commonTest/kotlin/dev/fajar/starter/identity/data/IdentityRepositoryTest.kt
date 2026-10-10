package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.data.datasources.*
import dev.fajar.starter.identity.data.dto.*
import dev.fajar.starter.identity.data.dto.AuthResponse
import dev.fajar.starter.identity.data.dto.OidcExchangeRequest
import dev.fajar.starter.identity.data.repositories.DefaultIdentityRepository
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class IdentityRepositoryTest {
    private val response =
        AuthResponse(
            UserDto("1", "Alex", "demo@example.com"),
            TokensDto("access", "refresh", Long.MAX_VALUE),
        )

    private class Remote(val execute: suspend () -> AuthResponse) : AuthRemoteDataSource {
        override suspend fun completeSso(request: OidcExchangeRequest): AuthResponse =
            error("Unused SSO exchange")

        override suspend fun signIn(request: SignInRequest) = execute()

        override suspend fun refresh(request: RefreshRequest) = execute()
    }

    @Test
    fun mapsRawResponseAndNeverExposesTokensInDiagnostics() = runTest {
        val repository = DefaultIdentityRepository(Remote { response })
        val result =
            assertIs<AppResult.Success<*>>(repository.signIn("demo@example.com", "password"))
        assertFalse(result.value.toString().contains("refresh"))
        assertFalse(SignInRequest("private@example.com", "secret").toString().contains("secret"))
    }

    @Test
    fun rejectsInvalidPayloadAndSanitizesTechnicalFailures() = runTest {
        val invalid =
            DefaultIdentityRepository(
                Remote { response.copy(tokens = response.tokens.copy(accessToken = "")) }
            )
        assertIs<AppResult.Failed>(invalid.signIn("demo@example.com", "secret"))
        val failure =
            DefaultIdentityRepository(Remote { error("private response") })
                .signIn("demo@example.com", "secret")
        assertFalse(assertIs<AppResult.Failed>(failure).failure.message.contains("private"))
    }

    @Test
    fun cancelledAcquisitionRejectsNonCooperativeLateSuccess() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository =
            DefaultIdentityRepository(
                Remote {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    response
                }
            )
        val job = async { repository.signIn("demo@example.com", "secret") }
        started.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
    }
}
