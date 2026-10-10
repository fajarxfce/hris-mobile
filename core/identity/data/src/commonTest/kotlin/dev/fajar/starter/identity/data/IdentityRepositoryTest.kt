package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.data.datasources.*
import dev.fajar.starter.identity.data.dto.*
import dev.fajar.starter.identity.data.dto.AuthResponse
import dev.fajar.starter.identity.data.repositories.DefaultIdentityRepository
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class IdentityRepositoryTest {
    private val server =
        dev.fajar.starter.identity.domain.entities.IdentityServer(
            "https://example.invalid/api/v1/",
            "Test device",
        )
    private val operation = "33333333-3333-4333-8333-333333333333"
    private val response =
        AuthResponse(
            AccountDto("11111111-1111-4111-8111-111111111111", "demo@example.com", "Alex", false),
            NativeCredentialsDto(
                "22222222-2222-4222-8222-222222222222",
                "access",
                "refresh",
                "2099-01-01T00:00:00Z",
                "2099-02-01T00:00:00Z",
                "Bearer",
            ),
        )

    private class Remote(val execute: suspend () -> AuthResponse) : AuthRemoteDataSource {
        override suspend fun signIn(request: SignInRequest, operationId: String) = execute()

        override suspend fun refresh(request: RefreshRequest, operationId: String) =
            execute().credentials
    }

    @Test
    fun mapsRawResponseAndNeverExposesTokensInDiagnostics() = runTest {
        val repository = DefaultIdentityRepository(Remote { response }, server)
        val result =
            assertIs<AppResult.Success<*>>(
                repository.signIn("demo@example.com", "password", "Test device", operation)
            )
        assertFalse(result.value.toString().contains("refresh"))
        assertFalse(
            SignInRequest("private@example.com", "secret", "Test device")
                .toString()
                .contains("secret")
        )
    }

    @Test
    fun rejectsInvalidPayloadAndSanitizesTechnicalFailures() = runTest {
        val invalid =
            DefaultIdentityRepository(
                Remote { response.copy(credentials = response.credentials.copy(accessToken = "")) },
                server,
            )
        assertIs<AppResult.Failed>(
            invalid.signIn("demo@example.com", "secret", "Test device", operation)
        )
        val failure =
            DefaultIdentityRepository(Remote { error("private response") }, server)
                .signIn("demo@example.com", "secret", "Test device", operation)
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
                },
                server,
            )
        val job = async {
            repository.signIn("demo@example.com", "secret", "Test device", operation)
        }
        started.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
    }
}
