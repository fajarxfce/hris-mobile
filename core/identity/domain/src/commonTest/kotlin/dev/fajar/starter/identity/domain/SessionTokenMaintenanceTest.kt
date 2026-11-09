@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.IdentityRepository
import dev.fajar.starter.identity.domain.sso.entities.SsoProof
import dev.fajar.starter.identity.domain.usecases.AcquireSessionTokens
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SessionTokenMaintenanceTest {
    @Test
    fun renewsBeforeEachExpiryWithoutRequestsBetweenCredentialDeadlines() = runTest {
        val f = RenewalFixture(testScheduler)
        val job = backgroundScope.launch { f.acquire.maintain(f.original.id).collect() }
        runCurrent()
        advanceTimeBy(569_999)
        runCurrent()
        assertEquals(0, f.calls.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, f.calls.size)
        assertNull(f.current().pendingRefreshOperationId)
        advanceTimeBy(569_999)
        runCurrent()
        assertEquals(1, f.calls.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, f.calls.size)
        assertNotEquals(f.calls[0], f.calls[1])
        assertTrue(job.isActive)
    }

    @Test
    fun recordingItsOwnRefreshIntentDoesNotCancelAnExchange() = runTest {
        val f = RenewalFixture(testScheduler)
        val release = CompletableDeferred<Unit>()
        f.beforeReply = { release.await() }
        backgroundScope.launch { f.acquire.maintain(f.original.id).collect() }
        runCurrent()
        advanceTimeBy(570_000)
        runCurrent()
        assertEquals(1, f.calls.size)
        assertNotNull(f.current().pendingRefreshOperationId)
        release.complete(Unit)
        runCurrent()
        assertNull(f.current().pendingRefreshOperationId)
        assertEquals("access-1", f.current().tokens.accessToken)
    }

    @Test
    fun concurrentMaintenanceAndRequestAcquisitionShareOneRotation() = runTest {
        val f = RenewalFixture(testScheduler)
        repeat(2) { backgroundScope.launch { f.acquire.maintain(f.original.id).collect() } }
        runCurrent()
        advanceTimeBy(570_000)
        val request = async { f.acquire(f.original.id) }
        runCurrent()
        assertEquals(
            "access-1",
            assertIs<AppResult.Success<SessionTokens?>>(request.await()).value?.accessToken,
        )
        assertEquals(1, f.calls.size)
    }

    @Test
    fun failedRevisionWaitsForAnExternalTriggerWithoutCreatingARetryLoop() = runTest {
        val f = RenewalFixture(testScheduler)
        val offline = AppResult.Failed(Failure(FailureKind.Network, "Offline"))
        f.failure = offline
        val results = mutableListOf<AppResult<SessionTokens?>>()
        backgroundScope.launch { f.acquire.maintain(f.original.id).collect(results::add) }
        runCurrent()
        advanceTimeBy(570_000)
        runCurrent()
        assertEquals<AppResult<SessionTokens?>>(offline, results.single())
        val pending = f.current().pendingRefreshOperationId
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, f.calls.size)
        assertEquals(pending, f.current().pendingRefreshOperationId)
        f.failure = null
        assertIs<AppResult.Success<SessionTokens?>>(f.acquire(f.original.id))
        assertEquals(f.calls[0], f.calls[1])
        runCurrent()
        assertEquals(2, f.calls.size)
    }

    @Test
    fun stoppingTheOwnerCancelsItsDeadline() = runTest {
        val f = RenewalFixture(testScheduler)
        val job = backgroundScope.launch { f.acquire.maintain(f.original.id).collect() }
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun replacementIdentityCannotBeRefreshedByTheOldOwner() = runTest {
        val f = RenewalFixture(testScheduler)
        backgroundScope.launch { f.acquire.maintain(f.original.id).collect() }
        runCurrent()
        f.sessions.value.value = AppResult.Success(f.original.copy(id = "replacement"))
        runCurrent()
        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(f.calls.isEmpty())
        assertEquals("replacement", f.current().id)
    }

    @Test
    fun externalRotationReplacesTheOldDeadline() = runTest {
        val f = RenewalFixture(testScheduler)
        backgroundScope.launch { f.acquire.maintain(f.original.id).collect() }
        runCurrent()
        advanceTimeBy(300_000)
        f.sessions.value.value =
            AppResult.Success(
                f.original.copy(
                    tokens =
                        f.original.tokens.copy(
                            accessToken = "external",
                            expiresAtEpochMillis = f.clock.now().toEpochMilliseconds() + 600_000,
                        )
                )
            )
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()
        assertTrue(f.calls.isEmpty())
        advanceTimeBy(270_000)
        runCurrent()
        assertEquals(1, f.calls.size)
    }

    @Test
    fun cancellationPreservesTheOriginalOperationForANewOwner() = runTest {
        val f = RenewalFixture(testScheduler)
        f.beforeReply = { awaitCancellation() }
        val job = backgroundScope.launch { f.acquire.maintain(f.original.id).collect() }
        runCurrent()
        advanceTimeBy(570_000)
        runCurrent()
        assertEquals(1, f.calls.size)
        job.cancelAndJoin()
        assertNotNull(f.current().pendingRefreshOperationId)
        f.beforeReply = {}
        backgroundScope.launch { f.acquire.maintain(f.original.id).collect() }
        runCurrent()
        assertEquals(2, f.calls.size)
        assertEquals(f.calls[0], f.calls[1])
        assertNull(f.current().pendingRefreshOperationId)
    }

    @Test
    fun foreignAudienceAndExpiredParentNeverStartRenewal() = runTest {
        for (binding in
            listOf(
                testSession().binding.copy(audience = "https://other.invalid/"),
                testSession().binding.copy(expiresAtEpochMillis = 0),
            )) {
            val f = RenewalFixture(testScheduler)
            f.sessions.value.value = AppResult.Success(f.original.copy(binding = binding))
            val job = backgroundScope.launch { f.acquire.maintain(f.original.id).collect() }
            runCurrent()
            advanceTimeBy(600_000)
            runCurrent()
            assertTrue(f.calls.isEmpty())
            job.cancelAndJoin()
        }
    }
}

private class RenewalFixture(scheduler: TestCoroutineScheduler) {
    val clock =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(1_000_000 + scheduler.currentTime)
        }
    val original =
        testSession()
            .copy(
                tokens =
                    testSession()
                        .tokens
                        .copy(expiresAtEpochMillis = clock.now().toEpochMilliseconds() + 600_000)
            )
    val sessions = TestSessions(original)
    val calls = mutableListOf<Pair<String, String>>()
    var beforeReply: suspend () -> Unit = {}
    var failure: AppResult.Failed? = null

    fun current() = assertNotNull(assertIs<AppResult.Success<Session?>>(sessions.value.value).value)

    private val remote =
        object : IdentityRepository {
            override suspend fun signIn(
                email: String,
                password: String,
                deviceName: String,
                operationId: String,
            ): AppResult<AuthenticatedUser> = error("unused")

            override suspend fun completeSso(proof: SsoProof): AppResult<AuthenticatedUser> =
                error("unused")

            override suspend fun refresh(
                session: Session,
                operationId: String,
            ): AppResult<AuthenticatedUser> {
                assertEquals(operationId, current().pendingRefreshOperationId)
                calls += operationId to session.tokens.refreshToken
                beforeReply()
                return failure
                    ?: AppResult.Success(
                        AuthenticatedUser(
                            session.user,
                            SessionTokens(
                                "access-${calls.size}",
                                "refresh-${calls.size}",
                                clock.now().toEpochMilliseconds() + 600_000,
                            ),
                            session.binding,
                        )
                    )
            }
        }
    val acquire =
        AcquireSessionTokens(
            remote,
            sessions,
            IdentityServer("https://example.invalid/", "Test"),
            clock,
        )
}
