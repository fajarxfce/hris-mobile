@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationSpaceRecoveryWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.RecoveryWorkerFixture
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceRecoveryWorkerTest {
    @Test
    fun locallyIdleRoomCompletesItsClaimWithoutDeliveryTransport() = runTest {
        val f = Fixture(testScheduler)
        f.needsDelivery = false
        assertEquals(SyncResult.Complete, f.worker())
        assertTrue(f.delivered.isEmpty())
        assertNotNull(f.wake)
        assertNotNull(f.progress?.sweep)
        assertEquals(SyncResult.Complete, f.worker())
        assertTrue(f.delivered.isEmpty())
        assertNull(f.wake)
        assertNull(f.progress?.sweep)
    }

    @Test
    fun failedDemandRetainsWakeAndBacksOffWithoutDelivery() = runTest {
        val f = Fixture(testScheduler)
        f.demandFailure = Failure(FailureKind.Storage, "fixture")
        assertIs<SyncResult.Retry>(f.worker())
        assertTrue(f.delivered.isEmpty())
        assertNotNull(f.wake)
        assertTrue(f.progress!!.sweep!!.retryRequired)
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun cancelledDemandRetainsClaimBeforeAnyDelivery() = runTest {
        val f = Fixture(testScheduler)
        val entered = CompletableDeferred<Unit>()
        f.beforeDemand = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch { f.worker() }
        entered.await()
        job.cancelAndJoin()
        assertNotNull(f.progress!!.sweep!!.pending)
        assertNotNull(f.wake)
        assertTrue(f.delivered.isEmpty())
    }

    @Test
    fun fourRawPagesPerTurnRetainTheSeekAndLateDuplicateRequestsDoNoWork() = runTest {
        val f = Fixture(testScheduler)
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(2, f.delivered.size)
        assertEquals(raw(4), f.progress!!.sweep!!.afterId)
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.scheduled)
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(4, f.delivered.size)
        assertNull(f.wake)
        assertEquals(1, f.acknowledgments)
        assertEquals(2, f.adoptions)
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(2, f.adoptions)
    }

    @Test
    fun newerWakeAtAcknowledgmentSurvivesTheCompletedSweep() = runTest {
        val f = Fixture(testScheduler, count = 2)
        val old = f.wake
        f.beforeAck = { f.wake = CollaborationSpaceRecoveryWake(id(999)) }
        assertEquals(SyncResult.Complete, f.worker())
        assertNotNull(f.wake)
        assertNotEquals(old, f.wake)
        assertNull(f.progress!!.sweep)
        assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.scheduled)
    }

    @Test
    fun oneProcessorOneWaiterAndNoThirdCapturedNativeWork() = runTest {
        val f = Fixture(testScheduler, count = 2)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeAdopt = {
            entered.complete(Unit)
            release.await()
        }
        val first = async { f.worker() }
        entered.await()
        val second = async { f.worker() }
        runCurrent()
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(1, f.adoptions)
        release.complete(Unit)
        assertEquals(SyncResult.Complete, first.await())
        assertEquals(SyncResult.Complete, second.await())
        assertEquals(1, f.adoptions)
    }

    @Test
    fun cancellationAfterClaimKeepsTheExactRoomForTheNextAttempt() = runTest {
        val f = Fixture(testScheduler, count = 2)
        val entered = CompletableDeferred<Unit>()
        f.beforeDelivery = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch { f.worker() }
        entered.await()
        job.cancelAndJoin()
        val retained = f.progress!!.sweep!!.pending!!.conversationId
        assertNotNull(retained)
        assertNotNull(f.wake)
        f.beforeDelivery = {}
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(listOf(retained, retained), f.delivered)
        assertNull(f.wake)
    }

    @Test
    fun changedPublishedParentCancelsInFlightAdoptionWithoutAcknowledgment() = runTest {
        val f = Fixture(testScheduler)
        val entered = CompletableDeferred<Unit>()
        var released = false
        f.beforeAdopt = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                released = true
            }
        }
        val result = async { f.worker() }
        entered.await()
        f.base.states.value = f.base.publication(owner = null)
        assertIs<SyncResult.Blocked>(result.await())
        assertTrue(released)
        assertNotNull(f.wake)
        assertNull(f.progress)
    }

    @Test
    fun unresolvedNativeReportBacksOffWithoutAnImmediateContinuationLoop() = runTest {
        val f = Fixture(testScheduler)
        f.report =
            CollaborationDeliveryReport(
                emptySet(),
                setOf(id(80)),
                CollaborationQueueDeliveryReason.STOPPED,
            )
        val result = assertIs<SyncResult.Retry>(f.worker())
        assertEquals(30L, result.failure!!.retryAfterSeconds)
        assertTrue(f.progress!!.sweep!!.retryRequired)
        assertTrue(f.scheduled.isEmpty())
        assertEquals(1, f.delivered.size)
        assertNotNull(f.wake)
    }

    @Test
    fun failedContinuationSchedulingKeepsTheCommittedSeekAndWake() = runTest {
        val f = Fixture(testScheduler)
        f.scheduleFailure = Failure(FailureKind.Unavailable, "Fixture scheduler")
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(raw(4), f.progress!!.sweep!!.afterId)
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
    }

    @Test
    fun absentOriginalRootCompletesWithoutAnyAdoptionSyncOrRoomWork() = runTest {
        val f = Fixture(testScheduler)
        f.original = null
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(0, f.adoptions)
        assertEquals(0, f.syncs)
        assertTrue(f.delivered.isEmpty())
        assertNull(f.wake)
    }

    @Test
    fun requestedDeliveryCannotCreateAWakeButScheduledDiscoveryCan() = runTest {
        val f = Fixture(testScheduler)
        f.wake = null
        f.original = null
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(0, f.deviceReads)
        assertEquals(SyncResult.Complete, f.worker.invoke(SyncTrigger.Scheduled))
        assertEquals(1, f.wakeWrites)
        assertEquals(1, f.deviceReads)
    }

    @Test
    fun unsupportedHostCannotAcknowledgeNativeWork() = runTest {
        val f = Fixture(testScheduler)
        assertEquals(SyncResult.Complete, f.createWorker(AppPlatform.Desktop)())
        assertNotNull(f.wake)
        assertEquals(0, f.deviceReads)
    }

    @Test
    fun confirmedDenialRetainsWakeWithoutWritingOrLooping() = runTest {
        val f = Fixture(testScheduler)
        f.syncFailure = Failure(FailureKind.AccessDenied, "Fixture denied")
        assertIs<SyncResult.Blocked>(f.worker())
        assertNull(f.progress)
        assertNotNull(f.wake)
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun wholeTurnTimeoutCancelsLocalWorkAndReleasesTheProcessor() = runTest {
        val f = Fixture(testScheduler)
        var released = false
        f.beforeDevice = {
            try {
                awaitCancellation()
            } finally {
                released = true
            }
        }
        assertEquals(FailureKind.Timeout, assertIs<SyncResult.Retry>(f.worker()).failure!!.kind)
        assertTrue(released)
        assertEquals(90_000L, testScheduler.currentTime)
        f.beforeDevice = {}
        f.original = null
        assertEquals(SyncResult.Complete, f.worker())
    }

    private class Fixture(scheduler: TestCoroutineScheduler, val count: Int = 8) {
        val base = RecoveryWorkerFixture(scheduler, count = 0)
        val owner = CollaborationSpaceOwner(base.parent, id(100))
        var original: CollaborationSpaceRecoveryDevice? =
            CollaborationSpaceRecoveryDevice(owner, id(101), id(102))
        val runtime = CollaborationSpaceRuntimeReady(owner, id(103), id(102))
        val bindingTime = base.clock.now()
        val boundary =
            CollaborationSpaceRoomBindingBoundary(
                owner.parent.audience,
                owner.parent.accountId,
                owner.spaceId,
                id(104),
                1,
            )
        var wake: CollaborationSpaceRecoveryWake? = CollaborationSpaceRecoveryWake(id(1))
        var progress: CollaborationSpaceRecoveryProgress? = null
        var writes = 0
        var acknowledgments = 0
        var wakeWrites = 0
        var deviceReads = 0
        var adoptions = 0
        var syncs = 0
        val delivered = mutableListOf<String>()
        val scheduled = mutableListOf<String>()
        var beforeDevice: suspend () -> Unit = {}
        var beforeAdopt: suspend () -> Unit = {}
        var beforeDemand: suspend () -> Unit = {}
        var needsDelivery = true
        var demandFailure: Failure? = null
        var beforeDelivery: suspend () -> Unit = {}
        var beforeAck: suspend () -> Unit = {}
        var scheduleFailure: Failure? = null
        var syncFailure: Failure? = null
        var report =
            CollaborationDeliveryReport(
                emptySet(),
                emptySet(),
                CollaborationQueueDeliveryReason.DRAINED,
            )
        val wakes =
            object : CollaborationSpaceRecoveryWakeRepository {
                override suspend fun pending() = AppResult.Success(wake)

                override suspend fun request(): AppResult<CollaborationSpaceRecoveryWake> {
                    val value = CollaborationSpaceRecoveryWake(id(500 + ++wakeWrites))
                    wake = value
                    return AppResult.Success(value)
                }

                override suspend fun acknowledge(
                    wake: CollaborationSpaceRecoveryWake
                ): AppResult<Boolean> {
                    beforeAck()
                    if (this@Fixture.wake != wake) return AppResult.Success(false)
                    this@Fixture.wake = null
                    acknowledgments++
                    return AppResult.Success(true)
                }
            }
        val schedules =
            object : SyncScheduleRepository {
                override suspend fun request(key: String): AppResult<Unit> {
                    scheduled += key
                    return scheduleFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
                }
            }
        val checkpoints =
            object : CollaborationSpaceRecoveryProgressRepository {
                override suspend fun current(owner: CollaborationSpaceOwner) =
                    AppResult.Success(progress)

                override suspend fun replace(
                    owner: CollaborationSpaceOwner,
                    expected: CollaborationSpaceRecoveryProgress?,
                    sweep: CollaborationSpaceRecoverySweep?,
                ): AppResult<CollaborationSpaceRecoveryProgress?> {
                    if (expected != progress) return AppResult.Success(null)
                    val saved = CollaborationSpaceRecoveryProgress(id(1000 + ++writes), sweep)
                    progress = saved
                    return AppResult.Success(saved)
                }
            }
        val retained =
            (0 until count step 2).associate { n -> binding(n).let { it.conversation.id to it } }
        val bindings =
            object : CollaborationSpaceRoomBindingRepository {
                override suspend fun getByConversation(
                    access: CollaborationSpaceLocalAccess,
                    conversationId: String,
                ) = AppResult.Success(retained[conversationId])

                override suspend fun findByRoom(
                    access: CollaborationSpaceLocalAccess,
                    serverName: String,
                    roomId: String,
                ): AppResult<CollaborationSpaceRoomBinding?> = error("No discovery")

                override suspend fun retain(
                    access: CollaborationSpaceLocalAccess,
                    binding: CollaborationSpaceRoomBinding,
                ): AppResult<Boolean> = error("No room creation")
            }
        val inventory =
            object : CollaborationSpaceRoomBindingInventoryRepository {
                override suspend fun page(
                    access: CollaborationSpaceLocalAccess,
                    after: CollaborationSpaceRoomBindingCursor?,
                    limit: Int,
                ): AppResult<CollaborationSpaceRoomBindingPage> {
                    assertEquals(1, limit)
                    val index = after?.afterId?.drop(2)?.toInt(16) ?: 0
                    val items =
                        if (index < count && index % 2 == 0) listOf(binding(index)) else emptyList()
                    return AppResult.Success(
                        CollaborationSpaceRoomBindingPage(
                            if (count == 0) null else boundary,
                            items,
                            if (index + 1 < count)
                                CollaborationSpaceRoomBindingCursor(boundary, raw(index + 1))
                            else null,
                            false,
                        )
                    )
                }
            }
        val worker = createWorker()

        fun createWorker(platform: AppPlatform = AppPlatform.Android) =
            CollaborationSpaceRecoveryWorker(
                states = lazy { base.states },
                ensureWake = EnsureCollaborationSpaceRecoveryWake(wakes),
                readWake = ReadCollaborationSpaceRecoveryWake(wakes),
                completeWake = CompleteCollaborationSpaceRecoveryWake(wakes),
                resume = lazy { ResumeCollaborationSpaceRecovery(wakes, schedules) },
                device = {
                    assertEquals(base.parent, it)
                    deviceReads++
                    beforeDevice()
                    AppResult.Success(original)
                },
                adopt = {
                    adoptions++
                    beforeAdopt()
                    AppResult.Success(runtime)
                },
                sync = {
                    syncs++
                    syncFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
                },
                readProgress =
                    ReadCollaborationSpaceRecoveryProgress(checkpoints, base.sessions, base.server),
                updateProgress =
                    UpdateCollaborationSpaceRecoveryProgress(
                        checkpoints,
                        base.sessions,
                        base.server,
                    ),
                page = LoadCollaborationSpaceRecoveryPage(inventory, base.sessions, base.server),
                room =
                    LoadCollaborationSpaceRecoveryRoom(
                        bindings,
                        inventory,
                        base.sessions,
                        base.server,
                    ),
                finishSweep =
                    FinishCollaborationSpaceRecovery(
                        checkpoints,
                        inventory,
                        base.sessions,
                        base.server,
                    ),
                demand = { _, _ ->
                    beforeDemand()
                    demandFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(needsDelivery)
                },
                deliver = { _, conversation ->
                    delivered += conversation.id
                    beforeDelivery()
                    AppResult.Success(report)
                },
                build = base.build.copy(platform = platform),
            )

        private fun binding(index: Int): CollaborationSpaceRoomBinding {
            val pair = listOf(owner.parent.accountId, id(6000)).sorted()
            val time = bindingTime
            val position = CollaborationOriginalConversationPosition(id(7000), id(8000 + index))
            val room = "!original$index:matrix.example.test"
            return CollaborationSpaceRoomBinding(
                CollaborationSpaceConversationMetadata(
                    owner.spaceId,
                    id(3000 + index),
                    pair[0],
                    pair[1],
                    pair[0],
                    time,
                    0,
                ),
                CollaborationSpacePublishedRoom(
                    room,
                    "matrix.example.test",
                    CollaborationSpaceRoomOrigin.LEGACY,
                    time,
                    CollaborationSpaceOriginalRoom(position, room, time - 60.seconds),
                ),
                CollaborationSpaceOriginalRoomMetadata(
                    CollaborationOriginalConversation(
                        position,
                        pair[0],
                        pair[1],
                        pair[0],
                        time - 120.seconds,
                        0,
                    ),
                    CollaborationOriginalRoom(
                        "matrix.example.test",
                        room,
                        time - 60.seconds,
                        pair[0],
                    ),
                ),
            )
        }
    }

    companion object {
        private fun id(n: Int) = "e0000000-0000-4000-8000-${n.toString().padStart(12, '0')}"

        private fun raw(n: Int) = "b-" + n.toString(16).padStart(64, '0')
    }
}
