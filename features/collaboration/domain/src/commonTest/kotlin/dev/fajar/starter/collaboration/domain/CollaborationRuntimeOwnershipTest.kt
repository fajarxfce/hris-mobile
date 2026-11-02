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
    fun leavingACompanyDropsForegroundRetentionWhileKeepingTheNativeParent() = runTest {
        val f = OwnershipFixture(testScheduler)
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        assertEquals(listOf<CollaborationOwner?>(f.owner), f.closedExcept)
        f.workspace.value = AppResult.Success(null)
        runCurrent()
        assertEquals(AppResult.Success(null), f.observed.last())
        assertNull(f.closedExcept.last())
        assertEquals(f.parent, f.retentions.last().parent)
        assertNull(f.retentions.last().foreground)
        f.workspace.value = AppResult.Success(f.selected)
        runCurrent()
        assertEquals(AppResult.Success(f.owner), f.observed.last())
        job.cancelAndJoin()
        assertNull(f.closedExcept.last())
        assertNull(f.retentions.last().parent)
    }

    @Test
    fun parentExpiryStillFiresAfterForegroundExpiryDespiteClockRollback() = runTest {
        val f = OwnershipFixture(testScheduler)
        val start = f.now()
        f.session.value =
            AppResult.Success(
                f.initial.copy(
                    binding =
                        f.initial.binding.copy(
                            expiresAtEpochMillis = (start + 2.seconds).toEpochMilliseconds()
                        )
                )
            )
        val policy = checkNotNull(f.selected.policy)
        f.workspace.value =
            AppResult.Success(
                f.selected.copy(
                    admission = f.selected.admission.copy(expiresAt = start + 2.seconds),
                    policy =
                        policy.copy(policy = policy.policy.copy(validUntil = start + 1.seconds)),
                )
            )
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        assertEquals(f.owner, f.retentions.last().foreground)
        f.clockOffset = -1.hours.inWholeMilliseconds
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(f.parent, f.retentions.last().parent)
        assertNull(f.retentions.last().foreground)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(f.retentions.last().parent)
        job.cancelAndJoin()
    }

    @Test
    fun unavailableWorkspaceCannotTurnAValidParentIntoLogout() = runTest {
        val f = OwnershipFixture(testScheduler)
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        f.workspace.value = f.setup.storageFailure
        runCurrent()
        assertEquals(f.parent, f.retentions.last().parent)
        assertNull(f.retentions.last().foreground)
        assertEquals(0, f.local.restricts)
        f.session.value = f.setup.storageFailure
        runCurrent()
        assertNull(f.retentions.last().parent)
        job.cancelAndJoin()
    }

    @Test
    fun companyDenialReachesTheRetainedGateDespiteStorageAndNativeCleanupFailure() = runTest {
        val f = OwnershipFixture(testScheduler)
        f.local.restrictResult = f.setup.storageFailure
        f.onClose = { f.setup.storageFailure }
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        f.workspace.value = AppResult.Success(f.selected.copy(permissions = emptySet()))
        runCurrent()
        assertEquals("permission_denied", f.local.gate.value.denial?.code)
        assertEquals(1, f.local.restricts)
        assertEquals(f.parent, f.retentions.last().parent)
        assertEquals(f.owner.companyId, f.retentions.last().deniedCompanyId)
        assertEquals(
            "permission_denied",
            assertIs<AppResult.Failed>(f.observed.last()).failure.code,
        )
        job.cancelAndJoin()
    }

    @Test
    fun everyCapturedAdmissionDeadlineClosesWithoutRepositoryActivityOrClockRenewal() = runTest {
        repeat(4) { scenario ->
            val f = OwnershipFixture(testScheduler)
            val start = f.now()
            val selected = f.selected
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
                                    f.initial.copy(
                                        binding =
                                            f.initial.binding.copy(
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
            assertEquals(AppResult.Success(f.owner), f.observed.single())
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
        val selected = f.selected
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
        assertEquals(listOf<AppResult<CollaborationOwner?>>(AppResult.Success(f.owner)), f.observed)
        assertEquals(listOf<CollaborationOwner?>(f.owner), f.closedExcept)
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
                f.initial.copy(tokens = f.initial.tokens.copy(accessToken = "renewed"))
            )
        runCurrent()
        assertEquals(listOf<CollaborationOwner?>(f.owner), f.closedExcept)
        assertEquals(1, f.observed.size)
        job.cancelAndJoin()
    }

    @Test
    fun permissionDenialRemainsVisibleWhenCleanupFailsAndObservationErrorsCloseAll() = runTest {
        val f = OwnershipFixture(testScheduler)
        val job = backgroundScope.launch { f.maintain().collect(f.observed::add) }
        runCurrent()
        f.onClose = { f.setup.storageFailure }
        f.workspace.value = AppResult.Success(f.selected.copy(permissions = emptySet()))
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
        assertEquals(listOf(f.owner, null), f.closedExcept)
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
        assertEquals(AppResult.Success(f.owner), f.maintain().first())
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
    val initial =
        setup.access.initial.copy(
            user = setup.access.initial.user.copy(id = "a1000000-0000-4000-8000-000000000001"),
            binding =
                setup.access.initial.binding.copy(serverId = "a1000000-0000-4000-8000-000000000002"),
        )
    val selected =
        setup.access.selected.copy(
            admission =
                setup.access.selected.admission.copy(
                    accountId = initial.user.id,
                    familyId = initial.binding.serverId,
                )
        )
    val owner =
        CollaborationOwner(
            initial.id,
            initial.binding.audience,
            initial.user.id,
            selected.company.id,
            initial.binding.serverId,
        )
    val parent =
        CollaborationNativeParent(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            owner.nativeSessionId,
        )
    val local = LocalListFixture()
    val retentions = mutableListOf<CollaborationRuntimeRetention>()
    private val started = scheduler.currentTime
    var clockOffset = 0L

    fun now() = setup.access.now + (scheduler.currentTime - started + clockOffset).milliseconds

    val session = MutableStateFlow<AppResult<Session?>>(AppResult.Success(initial))
    val workspace = MutableStateFlow<AppResult<Workspace?>>(AppResult.Success(selected))
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
            override suspend fun updateRetention(
                retention:
                    dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeRetention
            ): AppResult<Unit> {
                retentions += retention
                return closeOtherOwners(retention.foreground)
            }

            override suspend fun acquireBackground(
                owner: CollaborationOwner,
                deviceId: String,
                lifetimeMillis: Long,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease?
            > = error("No implicit background runtime")

            override suspend fun activatePusher(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Unit> = error("No implicit pusher activation")

            override suspend fun selfIdentity(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.entities.CollaborationSelfIdentity?
            > = error("No implicit identity acquisition")

            override suspend fun prepareHistoryReopen(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<Boolean> = error("No implicit history reopening")

            override suspend fun readProviderHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                position: String?,
            ): AppResult<CollaborationProviderHistoryPage> =
                error("No implicit provider history page")

            override suspend fun findHistoryHead(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationHistoryHead> = error("No implicit history head lookup")

            override suspend fun readOriginalHistory(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
                anchorEventId: String,
            ): AppResult<CollaborationOriginalWindow> = error("No implicit history acquisition")

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
            local.replica,
            sessions,
            workspaces,
            setup.server,
            setup.build,
            object : Clock {
                override fun now() = this@OwnershipFixture.now()
            },
            scheduler.timeSource,
        )
}
