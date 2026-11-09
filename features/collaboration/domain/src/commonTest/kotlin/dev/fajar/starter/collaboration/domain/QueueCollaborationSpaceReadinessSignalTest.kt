@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationSpaceReadinessSignal
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class QueueCollaborationSpaceReadinessSignalTest {
    @Test
    fun serialObservationSurvivesMappedFailureAndStopsPendingPersistenceOnCancellation() = runTest {
        val f = Fixture()
        val outcomes = mutableListOf<AppResult<Boolean>>()
        val observed = Channel<AppResult<Boolean>>(Channel.UNLIMITED)
        val collecting =
            launch(start = CoroutineStart.UNDISPATCHED) {
                f.receiver().observe().collect {
                    outcomes += it
                    observed.send(it)
                }
            }
        f.readFailure = f.push.storage
        f.signals.emit(f.signal(1))
        assertEquals(AppResult.Failed(f.push.storage), observed.receive())
        f.readFailure = null
        f.signals.emit(f.signal(1))
        assertEquals(AppResult.Success(true), observed.receive())
        repeat(100) {
            f.signals.emit(f.signal(1))
            assertEquals(AppResult.Success(false), observed.receive())
        }
        assertEquals(1, f.push.writes)
        val entered = CompletableDeferred<Unit>()
        var closed = false
        f.beforeWrite = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                closed = true
            }
        }
        f.signals.emit(f.signal(2))
        entered.await()
        collecting.cancelAndJoin()
        assertTrue(closed)
        assertEquals(0, f.signals.subscriptionCount.value)
        assertEquals(102, outcomes.size)
        assertEquals(CollaborationReadinessProgress(1, 1), f.receipt?.progress)
        observed.close()
    }

    @Test
    fun commitRevisionCoalescesDuplicatesReorderingAndRecreatedConsumer() = runTest {
        for (platform in listOf(AppPlatform.Android, AppPlatform.Ios)) {
            val f = Fixture()
            f.push.beforeWrite = {
                assertEquals(CollaborationReadinessProgress(0, 7), f.receipt?.progress)
            }
            f.push.beforeSchedule = { assertNotNull(f.push.retained) }
            assertEquals(AppResult.Success(true), f.receiver(platform)(f.signal(7)))
            assertEquals(CollaborationReadinessProgress(7, 7), f.receipt?.progress)
            repeat(100) {
                assertEquals(
                    AppResult.Success(false),
                    f.receiver(platform)(f.signal(if (it % 2 == 0) 7 else 2)),
                )
            }
            assertEquals(1, f.push.writes)
            assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.push.scheduled)
            f.push.beforeWrite = {}
            assertEquals(AppResult.Success(true), f.receiver(platform)(f.signal(Long.MAX_VALUE)))
            assertEquals(
                CollaborationReadinessProgress(Long.MAX_VALUE, Long.MAX_VALUE),
                f.receipt?.progress,
            )
            assertEquals(2, f.push.writes)
        }
    }

    @Test
    fun foreignSourceParentAccountOrganizationDeviceAndUnsupportedHostNeverPersistOrWake() =
        runTest {
            val sample = Fixture().signal(1)
            val source = sample.source
            for (signal in
                listOf(
                    sample.copy(
                        source =
                            source.copy(
                                owner =
                                    source.owner.copy(
                                        parent = source.owner.parent.copy(sessionId = "replaced")
                                    )
                            )
                    ),
                    sample.copy(
                        source =
                            source.copy(
                                owner =
                                    source.owner.copy(
                                        parent = source.owner.parent.copy(accountId = id(12))
                                    )
                            )
                    ),
                    sample.copy(
                        source =
                            source.copy(
                                owner =
                                    source.owner.copy(
                                        parent = source.owner.parent.copy(nativeSessionId = id(13))
                                    )
                            )
                    ),
                    sample.copy(
                        source =
                            source.copy(
                                owner =
                                    source.owner.copy(
                                        parent =
                                            source.owner.parent.copy(
                                                audience = "https://elsewhere.test"
                                            )
                                    )
                            )
                    ),
                    sample.copy(source = source.copy(owner = source.owner.copy(spaceId = id(14)))),
                    sample.copy(source = source.copy(deviceId = id(15))),
                )) {
                val f = Fixture()
                assertEquals(AppResult.Success(false), f.receiver()(signal))
                assertEquals(0, f.reads)
                assertEquals(0, f.runtimeChecks)
                assertEquals(0, f.push.writes)
            }
            for (platform in listOf(AppPlatform.Web, AppPlatform.Desktop)) {
                val f = Fixture()
                assertIs<AppResult.Failed>(f.receiver(platform)(f.signal(1)))
                assertNull(f.receipt)
                assertEquals(0, f.push.writes)
            }
        }

    @Test
    fun shortCredentialExpiryDoesNotHideTheOwnedWake() = runTest {
        val f = Fixture()
        f.push.session =
            f.push.initial.copy(
                tokens = f.push.initial.tokens.copy(expiresAtEpochMillis = 0),
                binding = f.push.initial.binding.copy(expiresAtEpochMillis = 0),
            )
        assertEquals(AppResult.Success(true), f.receiver()(f.signal(1)))
        assertEquals(CollaborationReadinessProgress(1, 1), f.receipt?.progress)
    }

    @Test
    fun failedWakeOrScheduleRetainsUnacknowledgedRevisionForAnActualLaterSignal() = runTest {
        for (atSchedule in listOf(false, true)) {
            val f = Fixture()
            if (atSchedule) f.push.scheduleFailure = f.push.unavailable
            else f.push.writeFailure = f.push.storage
            assertEquals(
                AppResult.Failed(if (atSchedule) f.push.unavailable else f.push.storage),
                f.receiver()(f.signal(5)),
            )
            assertEquals(CollaborationReadinessProgress(0, 5), f.receipt?.progress)
            assertTrue(f.push.scheduled.isEmpty())
            f.push.scheduleFailure = null
            f.push.writeFailure = null
            assertEquals(AppResult.Success(true), f.receiver()(f.signal(3)))
            assertEquals(CollaborationReadinessProgress(5, 5), f.receipt?.progress)
            assertEquals(1, f.push.scheduled.size)
        }
    }

    @Test
    fun concurrentHigherRevisionSurvivesAnOlderSchedulingCompletion() = runTest {
        val f = Fixture()
        f.push.afterSchedule = { f.receipt = receipt(20, 0, 9) }
        assertEquals(AppResult.Success(true), f.receiver()(f.signal(5)))
        assertEquals(CollaborationReadinessProgress(5, 9), f.receipt?.progress)
        f.push.afterSchedule = {}
        assertEquals(AppResult.Success(true), f.receiver()(f.signal(9)))
        assertEquals(CollaborationReadinessProgress(9, 9), f.receipt?.progress)
    }

    @Test
    fun scopeReplacementAroundEachDurableBoundaryCannotScheduleOrAcknowledgeOldWork() = runTest {
        for (stage in 0..3) {
            val f = Fixture()
            val replace: suspend () -> Unit = {
                f.push.session = f.push.initial.copy(id = "replacement")
            }
            when (stage) {
                0 -> f.afterWrite = replace
                1 -> f.push.afterWrite = replace
                2 -> f.push.afterSchedule = replace
                3 -> f.afterWrite = { if (f.writes == 2) replace() }
            }
            assertEquals(
                "client.session_changed",
                assertIs<AppResult.Failed>(f.receiver()(f.signal(4))).failure.code,
            )
            assertEquals(if (stage >= 2) 1 else 0, f.push.scheduled.size)
            assertEquals(if (stage == 3) 4L else 0L, f.receipt?.progress?.requestedRevision)
        }
    }

    @Test
    fun compareAndSetContentionIsFiniteAndKeepsCommittedWork() = runTest {
        for (duringAck in listOf(false, true)) {
            val f = Fixture()
            if (duringAck) f.push.afterSchedule = { f.loseWrites = 10 } else f.loseWrites = 10
            assertEquals(
                "client.collaboration_space_readiness_changed",
                assertIs<AppResult.Failed>(f.receiver()(f.signal(2))).failure.code,
            )
            assertEquals(if (duringAck) 4 else 3, f.writes)
            assertEquals(
                if (duringAck) CollaborationReadinessProgress(0, 2) else null,
                f.receipt?.progress,
            )
            assertEquals(if (duringAck) 1 else 0, f.push.scheduled.size)
        }
    }

    @Test
    fun storageFailurePreservesClassificationWithoutCreatingAWake() = runTest {
        val f = Fixture()
        f.readFailure = f.push.storage
        assertEquals(AppResult.Failed(f.push.storage), f.receiver()(f.signal(1)))
        assertEquals(0, f.push.writes)
        assertNull(f.receipt)
    }

    @Test
    fun cancellationAfterRetentionOrSchedulingPreservesFactsAndRejectsLateSuccess() = runTest {
        for (afterSchedule in listOf(false, true)) {
            val f = Fixture()
            val cancel: suspend () -> Unit = { currentCoroutineContext().cancel() }
            if (afterSchedule) f.push.afterSchedule = cancel else f.afterWrite = cancel
            val job = launch {
                f.receiver()(f.signal(8))
                error("Cancelled control returned")
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(CollaborationReadinessProgress(0, 8), f.receipt?.progress)
            assertEquals(if (afterSchedule) 1 else 0, f.push.scheduled.size)
        }
    }

    @Test
    fun pendingReadsAndSchedulerHaveFiniteTimeoutAndJoinOnCancellation() = runTest {
        for (stage in 0..2) {
            val f = Fixture()
            var closed = false
            val wait: suspend () -> Unit = {
                try {
                    awaitCancellation()
                } finally {
                    closed = true
                }
            }
            when (stage) {
                0 -> f.beforeRead = wait
                1 -> f.beforeWrite = wait
                2 -> f.push.beforeSchedule = wait
            }
            val start = testScheduler.currentTime
            val failed = assertIs<AppResult.Failed>(f.receiver()(f.signal(1)))
            assertEquals(FailureKind.Timeout, failed.failure.kind)
            assertEquals(if (stage == 2) 1_500L else 3_000L, testScheduler.currentTime - start)
            assertTrue(closed)
            assertEquals(stage == 2, f.receipt != null)
        }
    }

    @Test
    fun closedOrDifferentInstalledOperationNeverReadsReceiptsOrRequestsWork() = runTest {
        for (differentOperation in listOf(false, true)) {
            val f = Fixture()
            val signal =
                f.signal(4).let {
                    if (differentOperation) it.copy(source = it.source.copy(operationId = id(99)))
                    else it
                }
            if (!differentOperation) f.runtimeOpen = false
            val failed = assertIs<AppResult.Failed>(f.receiver()(signal)).failure
            assertEquals("client.collaboration_space_readiness_runtime_changed", failed.code)
            assertEquals(FailureKind.Unavailable, failed.kind)
            assertEquals(0, f.reads)
            assertEquals(0, f.push.writes)
            assertTrue(f.push.scheduled.isEmpty())
        }
    }

    @Test
    fun runtimeRetirementAtEachDurableBoundaryLeavesOnlyActuallyCommittedProgress() = runTest {
        for (stage in 0..4) {
            val f = Fixture()
            val retire: suspend () -> Unit = { f.runtimeOpen = false }
            when (stage) {
                0 -> f.beforeRead = retire
                1 -> f.afterWrite = retire
                2 -> f.push.afterWrite = retire
                3 -> f.push.afterSchedule = retire
                4 -> f.afterWrite = { if (f.writes == 2) retire() }
            }
            val failed = assertIs<AppResult.Failed>(f.receiver()(f.signal(4))).failure
            assertEquals("client.collaboration_space_readiness_runtime_changed", failed.code)
            assertEquals(if (stage >= 3) 1 else 0, f.push.scheduled.size)
            assertEquals(
                if (stage == 0) null else if (stage == 4) 4L else 0L,
                f.receipt?.progress?.requestedRevision,
            )
        }
    }

    @Test
    fun runtimeLookupPreservesFailureAndJoinsAStalledReadAtTheReceiverDeadline() = runTest {
        val failed = Fixture()
        failed.runtimeFailure = failed.push.storage
        assertEquals(AppResult.Failed(failed.push.storage), failed.receiver()(failed.signal(1)))
        assertEquals(0, failed.reads)
        val waiting = Fixture()
        var stopped = false
        waiting.beforeRuntimeCheck = {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val started = testScheduler.currentTime
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(waiting.receiver()(waiting.signal(1))).failure.kind,
        )
        assertEquals(3_000L, testScheduler.currentTime - started)
        assertTrue(stopped)
        assertNull(waiting.receipt)
    }

    @Test
    fun everyRuntimeGuardUsesTheExactLogicalOwnerDeviceAndBootstrapOperation() = runTest {
        val f = Fixture()
        val signal = f.signal(3)
        assertEquals(AppResult.Success(true), f.receiver()(signal))
        assertTrue(f.checkedSources.size > 1)
        assertTrue(f.checkedSources.all { it == signal.source })
        assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.push.scheduled)
    }

    @Test
    fun parentReplacementDuringRuntimeLookupCannotReachDurableStorage() = runTest {
        val f = Fixture()
        f.beforeRuntimeCheck = { f.push.session = f.push.initial.copy(id = "replacement") }
        assertEquals(
            "client.session_changed",
            assertIs<AppResult.Failed>(f.receiver()(f.signal(3))).failure.code,
        )
        assertEquals(0, f.reads)
        assertEquals(0, f.push.writes)
    }

    private class Fixture {
        val push = CollaborationPushFixture()
        val signals = MutableSharedFlow<CollaborationSpaceReadinessSignal>()
        var runtimeOpen = true
        var runtimeFailure: Failure? = null
        var beforeRuntimeCheck: suspend () -> Unit = {}
        var runtimeChecks = 0
        val checkedSources = mutableListOf<CollaborationSpaceRuntimeReady>()
        private val runtimes =
            object : CollaborationSpaceRuntimeRepository {
                override fun spaceReadinessSignals() = signals.asSharedFlow()

                override suspend fun isSpaceOpen(
                    owner: CollaborationSpaceOwner,
                    deviceId: String,
                    operationId: String,
                ): AppResult<Boolean> {
                    runtimeChecks++
                    val observed = CollaborationSpaceRuntimeReady(owner, deviceId, operationId)
                    checkedSources += observed
                    beforeRuntimeCheck()
                    return runtimeFailure?.let { AppResult.Failed(it) }
                        ?: AppResult.Success(runtimeOpen && observed == signal(1).source)
                }

                override suspend fun acquireOriginalQueue(
                    runtime: CollaborationSpaceRuntimeReady,
                    matrixRoomId: String,
                ): AppResult<CollaborationSpaceOriginalQueueLease> = error("No queue acquisition")

                override suspend fun inspectOriginalQueue(
                    runtime: CollaborationSpaceRuntimeReady,
                    matrixRoomId: String,
                ): AppResult<CollaborationSpaceOriginalQueueSnapshot> = error("No queue inspection")

                override suspend fun installOriginal(
                    owner: CollaborationSpaceOwner,
                    original: CollaborationExistingRuntimeHandover,
                    setup: CollaborationSpaceSetupRecord,
                ): AppResult<CollaborationSpaceRuntimeLease> = error("No runtime acquisition")

                override suspend fun closeSpace(owner: CollaborationSpaceOwner): AppResult<Unit> =
                    error("No runtime mutation")
            }
        private val wakes =
            object : CollaborationSpaceRecoveryWakeRepository {
                override suspend fun pending(): AppResult<CollaborationSpaceRecoveryWake?> =
                    error("No worker scan")

                override suspend fun acknowledge(
                    wake: CollaborationSpaceRecoveryWake
                ): AppResult<Boolean> = error("No worker ACK")

                override suspend fun request(): AppResult<CollaborationSpaceRecoveryWake> {
                    push.calls += "write"
                    push.beforeWrite()
                    push.writeFailure?.let {
                        return AppResult.Failed(it)
                    }
                    val wake = CollaborationSpaceRecoveryWake(id(200 + ++push.writes))
                    // Shared fixture records the retained generation for schedule-order assertions.
                    push.retained = CollaborationRecoveryWake(wake.generation)
                    push.afterWrite()
                    return AppResult.Success(wake)
                }
            }
        var receipt: CollaborationReadinessReceipt? = null
        var reads = 0
        var writes = 0
        var loseWrites = 0
        var readFailure: Failure? = null
        var beforeRead: suspend () -> Unit = {}
        var beforeWrite: suspend () -> Unit = {}
        var afterWrite: suspend () -> Unit = {}
        private val receipts =
            object : CollaborationSpaceReadinessReceiptRepository {
                override suspend fun current(
                    scope: CollaborationSpaceReadinessScope
                ): AppResult<CollaborationReadinessReceipt?> {
                    reads++
                    beforeRead()
                    return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(receipt)
                }

                override suspend fun replace(
                    scope: CollaborationSpaceReadinessScope,
                    expected: CollaborationReadinessReceipt?,
                    progress: CollaborationReadinessProgress,
                ): AppResult<CollaborationReadinessReceipt?> {
                    writes++
                    beforeWrite()
                    if (loseWrites > 0) {
                        loseWrites--
                        return AppResult.Success(null)
                    }
                    if (expected != receipt) return AppResult.Success(null)
                    val saved = CollaborationReadinessReceipt(id(100 + writes), progress)
                    receipt = saved
                    afterWrite()
                    return AppResult.Success(saved)
                }
            }

        fun signal(revision: Long): CollaborationSpaceReadinessSignal {
            val s = push.initial
            val parent =
                CollaborationNativeParent(s.id, s.binding.audience, s.user.id, s.binding.serverId)
            return CollaborationSpaceReadinessSignal(
                CollaborationSpaceRuntimeReady(
                    CollaborationSpaceOwner(parent, id(2)),
                    id(3),
                    id(40),
                ),
                CollaborationSpaceReadinessControl(
                    id(1),
                    id(2),
                    s.user.id,
                    s.binding.serverId,
                    id(3),
                    revision,
                    1_794_171_600_123L,
                ),
            )
        }

        fun receiver(platform: AppPlatform = AppPlatform.Android) =
            QueueCollaborationSpaceReadinessSignal(
                push.sessions,
                receipts,
                wakes,
                push.schedules,
                IdentityServer(push.initial.binding.audience, "Fictional readiness"),
                AppBuild(platform, 76, "test", null),
                runtimes,
            )
    }

    companion object {
        private fun id(value: Int) = "a1000000-0000-4000-8000-" + value.toString().padStart(12, '0')

        private fun receipt(generation: Int, requested: Long, pending: Long) =
            CollaborationReadinessReceipt(
                id(generation),
                CollaborationReadinessProgress(requested, pending),
            )
    }
}
