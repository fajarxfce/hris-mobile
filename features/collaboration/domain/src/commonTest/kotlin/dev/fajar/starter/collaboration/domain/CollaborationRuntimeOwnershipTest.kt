@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.MaintainCollaborationRuntimeOwnership
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationRuntimeOwnershipTest {
    @Test
    fun leavingACompanyClosesAllResourcesAndReturningDoesNotOpenAnotherSession() = runTest {
        val f = OwnershipFixture(testScheduler)
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        assertEquals(listOf<CollaborationOwner?>(f.setup.owner), f.closedExcept)
        f.workspace.value = AppResult.Success(null)
        runCurrent()
        assertEquals(AppResult.Success(null), f.observed.last())
        assertNull(f.closedExcept.last())
        f.workspace.value = AppResult.Success(f.setup.access.selected)
        runCurrent()
        assertEquals(AppResult.Success(f.setup.owner), f.observed.last())
        job.cancelAndJoin()
        assertNull(f.closedExcept.last())
    }

    @Test
    fun everyCapturedAdmissionDeadlineClosesWithoutRepositoryActivityOrClockRenewal() = runTest {
        repeat(4) { scenario ->
            val f = OwnershipFixture(testScheduler)
            val start = f.now()
            val selected = f.setup.access.selected
            val policy = checkNotNull(selected.policy)
            val ending = start + 1.seconds
            f.workspace.value =
                AppResult.Success(
                    when (scenario) {
                        0 ->
                            selected.copy(
                                policy =
                                    policy.copy(policy = policy.policy.copy(validUntil = ending))
                            )
                        1 -> selected.copy(admission = selected.admission.copy(expiresAt = ending))
                        2 -> {
                            f.session.value =
                                AppResult.Success(
                                    f.setup.access.initial.copy(
                                        binding =
                                            f.setup.access.initial.binding.copy(
                                                expiresAtEpochMillis = ending.toEpochMilliseconds()
                                            )
                                    )
                                )
                            selected.copy(admission = selected.admission.copy(expiresAt = ending))
                        }
                        else ->
                            selected.copy(
                                policy =
                                    policy.copy(
                                        policy =
                                            policy.policy.copy(
                                                maintenance =
                                                    CompanyMaintenance(ending, ending + 1.hours)
                                            )
                                    )
                            )
                    }
                )
            val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
            runCurrent()
            assertEquals(AppResult.Success(f.setup.owner), f.observed.single())
            f.clockOffset = -1.hours.inWholeMilliseconds
            advanceTimeBy(999)
            runCurrent()
            assertEquals(1, f.observed.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                "client.collaboration_revalidation_required",
                assertIs<AppResult.Failed>(f.observed.last()).failure.code,
            )
            assertNull(f.closedExcept.last())
            val calls = f.closedExcept.size
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(calls, f.closedExcept.size)
            job.cancelAndJoin()
        }
    }

    @Test
    fun aFreshPolicyReplacesItsOldTimerWithoutCreatingASecondOwner() = runTest {
        val f = OwnershipFixture(testScheduler)
        val selected = f.setup.access.selected
        val policy = checkNotNull(selected.policy)
        f.workspace.value =
            AppResult.Success(
                selected.copy(
                    policy =
                        policy.copy(policy = policy.policy.copy(validUntil = f.now() + 1.seconds))
                )
            )
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        advanceTimeBy(500)
        f.workspace.value =
            AppResult.Success(
                selected.copy(
                    policy =
                        policy.copy(
                            requestedAt = f.now(),
                            policy =
                                policy.policy.copy(
                                    serverTime = f.now(),
                                    validUntil = f.now() + 2.seconds,
                                ),
                        )
                )
            )
        runCurrent()
        advanceTimeBy(501)
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationOwner?>>(AppResult.Success(f.setup.owner)),
            f.observed,
        )
        assertEquals(listOf<CollaborationOwner?>(f.setup.owner), f.closedExcept)
        advanceTimeBy(1_499)
        runCurrent()
        assertIs<AppResult.Failed>(f.observed.last())
        assertNull(f.closedExcept.last())
        job.cancelAndJoin()
    }

    @Test
    fun nativeTokenRotationDoesNotCloseTheCurrentOwner() = runTest {
        val f = OwnershipFixture(testScheduler)
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        f.session.value =
            AppResult.Success(
                f.setup.access.initial.copy(
                    tokens = f.setup.access.initial.tokens.copy(accessToken = "renewed")
                )
            )
        runCurrent()
        assertEquals(listOf<CollaborationOwner?>(f.setup.owner), f.closedExcept)
        assertEquals(1, f.observed.size)
        job.cancelAndJoin()
    }

    @Test
    fun permissionDenialRemainsVisibleWhenCleanupFailsAndObservationErrorsCloseAll() = runTest {
        val f = OwnershipFixture(testScheduler)
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        f.onClose = { f.setup.storageFailure }
        f.workspace.value =
            AppResult.Success(f.setup.access.selected.copy(permissions = emptySet()))
        runCurrent()
        assertEquals(
            "permission_denied",
            assertIs<AppResult.Failed>(f.observed.last()).failure.code,
        )
        assertNull(f.closedExcept.last())
        f.session.value = f.setup.storageFailure
        runCurrent()
        assertEquals(f.setup.storageFailure, f.observed.last())
        assertNull(f.closedExcept.last())
        job.cancelAndJoin()
    }

    @Test
    fun anObsoleteCleanupResponseCannotRepublishItsEarlierOwner() = runTest {
        val f = OwnershipFixture(testScheduler)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onClose = { retained ->
            if (retained != null) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            AppResult.Success(Unit)
        }
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        entered.await()
        f.workspace.value = AppResult.Success(null)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf<AppResult<CollaborationOwner?>>(AppResult.Success(null)), f.observed)
        assertEquals(listOf(f.setup.owner, null), f.closedExcept)
        job.cancelAndJoin()
    }

    @Test
    fun aSecondCollectorCannotAcquireOrCloseAnotherCollectorsRuntime() = runTest {
        val f = OwnershipFixture(testScheduler)
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        val calls = f.closedExcept.size
        assertEquals(
            "client.collaboration_owner_busy",
            assertIs<AppResult.Failed>(f.maintain().first()).failure.code,
        )
        assertEquals(calls, f.closedExcept.size)
        job.cancelAndJoin()
        assertNull(f.closedExcept.last())
        assertEquals(AppResult.Success(f.setup.owner), f.maintain().first())
        assertNull(f.closedExcept.last())
    }

    @Test
    fun cancellationStillClosesAllOwnersInAnActiveCleanupContext() = runTest {
        val f = OwnershipFixture(testScheduler)
        val entered = CompletableDeferred<Unit>()
        f.onClose = { owner ->
            if (owner != null) {
                entered.complete(Unit)
                awaitCancellation()
            }
            assertTrue(currentCoroutineContext().isActive)
            AppResult.Success(Unit)
        }
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        entered.await()
        job.cancelAndJoin()
        assertNull(f.closedExcept.last())
        assertTrue(f.observed.isEmpty())
    }
}

private class OwnershipFixture(private val scheduler: TestCoroutineScheduler) {
    val setup = CollaborationCommandFixture()
    private val started = scheduler.currentTime
    var clockOffset = 0L

    fun now() = setup.access.now + (scheduler.currentTime - started + clockOffset).milliseconds

    val session = MutableStateFlow<AppResult<Session?>>(AppResult.Success(setup.access.initial))
    val workspace =
        MutableStateFlow<AppResult<Workspace?>>(AppResult.Success(setup.access.selected))
    val observed = mutableListOf<AppResult<CollaborationOwner?>>()
    val closedExcept = mutableListOf<CollaborationOwner?>()
    var onClose: suspend (CollaborationOwner?) -> AppResult<Unit> = { AppResult.Success(Unit) }
    private val sessions =
        object : SessionRepository by setup.access.sessions {
            override fun observe() = session
        }
    private val workspaces =
        object : WorkspaceRepository by setup.access.workspaces {
            override fun observe(sessionId: String) = workspace
        }
    private val runtimes =
        object : CollaborationRuntimeRepository {
            override suspend fun acquireMessageQueue(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
            > = error("No implicit queue acquisition")

            override suspend fun isOpen(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Boolean> = error("No implicit runtime probe")

            override suspend fun enterTimeline(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationTimelineLease> = error("No implicit conversation entry")

            override suspend fun install(
                owner: CollaborationOwner,
                command: CollaborationSetupCommandRecord,
            ): AppResult<CollaborationRuntimeLease> = error("No implicit installation")

            override suspend fun restore(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationRuntimeLease?> = error("No implicit restore")

            override suspend fun closeOtherOwners(
                retainedOwner: CollaborationOwner?
            ): AppResult<Unit> {
                closedExcept.add(retainedOwner)
                return onClose(retainedOwner)
            }
        }
    val maintain =
        MaintainCollaborationRuntimeOwnership(
            runtimes,
            sessions,
            workspaces,
            setup.server,
            setup.build,
            object : Clock {
                override fun now() = this@OwnershipFixture.now()
            },
        )
}
