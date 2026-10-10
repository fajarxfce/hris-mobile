package dev.fajar.starter.identity.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.IdentityRepository
import dev.fajar.starter.identity.domain.sso.entities.SsoProof
import dev.fajar.starter.identity.domain.usecases.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class NativeSessionPolicyTest {
    private val server = IdentityServer("https://example.invalid/", "Test Android")

    private class Remote : IdentityRepository {
        var calls = 0
        var operations = mutableListOf<String>()
        var failure: AppResult.Failed? = null

        override suspend fun completeSso(proof: SsoProof): AppResult<AuthenticatedUser> =
            error("Unused")

        override suspend fun signIn(
            email: String,
            password: String,
            deviceName: String,
            operationId: String,
        ): AppResult<AuthenticatedUser> {
            calls++
            operations += operationId
            return failure
                ?: AppResult.Success(
                    AuthenticatedUser(
                        testSession().user,
                        testSession().tokens,
                        testSession().binding,
                    )
                )
        }

        override suspend fun refresh(
            session: Session,
            operationId: String,
        ): AppResult<AuthenticatedUser> {
            calls++
            return AppResult.Success(
                AuthenticatedUser(session.user, session.tokens, session.binding)
            )
        }
    }

    @Test
    fun anAudienceChangeOrExpiredFamilyClearsLocalCredentialsWithoutSendingThem() = runTest {
        for (binding in
            listOf(
                testSession().binding.copy(audience = "https://other.invalid/"),
                testSession().binding.copy(expiresAtEpochMillis = 1),
            )) {
            val original = testSession().copy(binding = binding)
            val sessions = TestSessions(original)
            val remote = Remote()
            assertEquals(
                AppResult.Success(null),
                AcquireSessionTokens(remote, sessions, server)(original.id),
            )
            assertEquals(AppResult.Success(null), sessions.current())
            assertEquals(0, remote.calls)
            sessions.compareAndSet(null, original)
            assertEquals(AppResult.Success(null), RestoreSession(sessions, server)())
        }
    }

    @Test
    fun lostPasswordResponsesReuseTheCallersOperationAndRetainTheRemoteFamily() = runTest {
        val sessions = TestSessions()
        val remote = Remote()
        val signIn = SignIn(remote, sessions, server)
        val operation = "22222222-2222-4222-8222-222222222222"
        remote.failure = AppResult.Failed(Failure(FailureKind.Network, "Offline"))
        assertSame(
            remote.failure,
            assertIs<AppResult.Failed>(signIn("demo@example.com", "password", operation)),
        )
        assertEquals(AppResult.Success(null), sessions.current())
        remote.failure = null
        assertIs<AppResult.Success<*>>(signIn("demo@example.com", "password", operation))
        val stored = assertIs<AppResult.Success<Session?>>(sessions.current()).value!!
        assertEquals(testSession().binding, stored.binding)
        assertNotEquals(stored.binding.serverId, stored.id)
        assertEquals(listOf(operation, operation), remote.operations)
    }
}
