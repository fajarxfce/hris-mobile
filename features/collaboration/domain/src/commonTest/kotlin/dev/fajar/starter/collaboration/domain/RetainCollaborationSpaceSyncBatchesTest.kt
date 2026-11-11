package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceSyncRepository
import dev.fajar.starter.collaboration.domain.usecases.RetainCollaborationSpaceSyncBatches
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RetainCollaborationSpaceSyncBatchesTest {
    @Test
    fun initialBatchRetainsOriginalSourcePositionAndWakeBeforeScheduling() = runTest {
        val f = IncomingBatchFixture()
        f.request.base.hooks["schedule"] = {
            val retained = assertNotNull(f.request.snapshot).demand
            assertEquals(f.batch.nextPosition, retained.sources.single().position)
            assertEquals(f.source, retained.sources.single().source)
            assertEquals(retained.sources.single().inventoryGeneration, f.request.wake?.generation)
            assertEquals(f.batch.rooms.single().roomId, retained.physicalRooms.single().roomId)
        }
        assertEquals(AppResult.Success(true), f.receive())
        assertEquals(1, f.request.writes)
        assertEquals(1, f.request.schedules)
        assertEquals(1, f.starts)
        assertEquals(1, f.stops)
        assertTrue(
            f.request.base.calls.all {
                it in setOf("demand", "write", "committed", "readback", "schedule")
            }
        )
    }

    @Test
    fun exactReplayAndContinuousEmptyResponsesDoNotWriteNewWakesOrSchedule() = runTest {
        val f = IncomingBatchFixture()
        assertEquals(AppResult.Success(true), f.receive())
        val retained = f.request.snapshot
        val wake = f.request.wake
        repeat(100) { assertEquals(AppResult.Success(false), f.receive()) }
        assertEquals(retained, f.request.snapshot)
        assertEquals(1, f.request.writes)
        f.responses.value =
            AppResult.Success(
                f.batch.copy(previousPosition = "next", nextPosition = "later", rooms = emptyList())
            )
        assertEquals(AppResult.Success(false), f.receive())
        assertEquals(2, f.request.writes)
        assertEquals(1, f.request.schedules)
        assertEquals(wake, f.request.wake)
        assertEquals(retained?.demand?.physicalRooms, f.request.snapshot?.demand?.physicalRooms)
    }

    @Test
    fun lostCommitReplyIsConfirmedEvenAfterWorkerHasCompletedAllWork() = runTest {
        val f = IncomingBatchFixture()
        f.request.responseLoss = true
        f.request.base.hooks["committed"] = {
            val saved = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                saved.copy(
                    generation = f.id(810),
                    demand =
                        saved.demand.copy(
                            physicalRooms = emptyList(),
                            sources =
                                saved.demand.sources.map { it.copy(inventoryGeneration = null) },
                        ),
                )
            f.request.wake = null
        }
        assertEquals(AppResult.Success(true), f.receive())
        assertEquals(1, f.request.writes)
        assertEquals("next", f.request.snapshot?.demand?.sources?.single()?.position)
        assertTrue(assertNotNull(f.request.snapshot).demand.physicalRooms.isEmpty())
        assertEquals(AppResult.Success(false), f.receive())
        assertEquals(1, f.request.writes)
    }

    @Test
    fun unrelatedWorkerChangesArePreservedDuringUncertainReadback() = runTest {
        val f = IncomingBatchFixture()
        f.request.responseLoss = true
        f.request.base.hooks["committed"] = {
            val saved = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                saved.copy(
                    generation = f.id(810),
                    demand = saved.demand.copy(rescanGeneration = f.id(820)),
                )
        }
        assertEquals(AppResult.Success(true), f.receive())
        assertEquals(f.id(820), f.request.snapshot?.demand?.rescanGeneration)
        assertEquals(1, f.request.writes)
    }

    @Test
    fun missingCommitOrWrongReadbackPositionCannotClaimDurabilityOrRetryWrite() = runTest {
        for (commit in listOf(false, true)) {
            val f = IncomingBatchFixture()
            f.request.commit = commit
            f.request.base.hooks["committed"] = {
                val saved = assertNotNull(f.request.snapshot)
                f.request.snapshot =
                    saved.copy(
                        demand =
                            saved.demand.copy(
                                sources =
                                    saved.demand.sources.map { it.copy(position = "different") }
                            )
                    )
            }
            assertIs<AppResult.Failed>(f.receive())
            assertEquals(1, f.request.writes)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun confirmedDenialWinsOverStorageFailureAfterEveryPendingStage() = runTest {
        for (stage in listOf("demand", "write", "committed", "readback", "schedule")) {
            val f = IncomingBatchFixture()
            val denied = f.request.base.directory.denied
            f.request.base.hooks[stage] = {
                f.request.base.directory.gate.value =
                    AppResult.Success(
                        CollaborationSpaceDirectoryAdmission(
                            f.request.base.directory.revision,
                            denied,
                        )
                    )
            }
            f.request.base.failures[stage] = f.request.base.directory.storageFailure
            assertEquals(denied, assertIs<AppResult.Failed>(f.receive()).failure, stage)
            assertEquals(if (stage == "schedule") 1 else 0, f.request.schedules, stage)
        }
    }

    @Test
    fun runtimeReplacementAfterEveryPendingStageRejectsOldBatch() = runTest {
        for (stage in listOf("demand", "write", "committed", "readback", "schedule")) {
            val f = IncomingBatchFixture()
            f.request.base.hooks[stage] = { f.installed = f.source.copy(storeId = "2".repeat(64)) }
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.receive()).failure.kind,
                stage,
            )
            assertEquals(if (stage == "schedule") 1 else 0, f.request.schedules, stage)
        }
    }

    @Test
    fun accountOrNativeSessionReplacementAfterEveryPendingStageRejectsOldBatch() = runTest {
        for (stage in listOf("demand", "write", "committed", "readback", "schedule")) {
            for (native in listOf(false, true)) {
                val f = IncomingBatchFixture()
                val setup = f.request.base.native.setup
                f.request.base.hooks[stage] = {
                    val session = assertNotNull(setup.session)
                    setup.session =
                        if (native)
                            session.copy(binding = session.binding.copy(serverId = f.id(900)))
                        else session.copy(user = session.user.copy(id = f.id(900)))
                }
                assertEquals(
                    FailureKind.Unauthorized,
                    assertIs<AppResult.Failed>(f.receive()).failure.kind,
                    stage,
                )
                assertEquals(if (stage == "schedule") 1 else 0, f.request.schedules, stage)
            }
        }
    }

    @Test
    fun foreignRuntimeSourceOrMalformedBatchCannotReadOrWriteDemand() = runTest {
        val sample = IncomingBatchFixture()
        for (batch in
            listOf(
                sample.batch.copy(runtime = sample.runtime.copy(operationId = sample.id(900))),
                sample.batch.copy(source = sample.source.copy(matrixDeviceId = "OTHER")),
                sample.batch.copy(source = sample.source.copy(storeId = "2".repeat(64))),
                sample.batch.copy(nextPosition = ""),
                sample.batch.copy(
                    rooms = List(65) { sample.batch.rooms.single().copy(roomId = "!$it:test") }
                ),
            )) {
            val f = IncomingBatchFixture()
            f.responses.value = AppResult.Success(batch)
            assertIs<AppResult.Failed>(f.receive())
            assertEquals(0, f.request.reads)
            assertEquals(0, f.request.writes)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun missingOrDeniedRuntimeNeverStartsSyncToRepairIt() = runTest {
        val f = IncomingBatchFixture()
        f.installed = null
        assertIs<AppResult.Failed>(f.receive())
        assertEquals(0, f.request.reads)
        f.installed = f.source
        val denied = f.request.base.directory.denied
        f.request.base.directory.gate.value =
            AppResult.Success(
                CollaborationSpaceDirectoryAdmission(f.request.base.directory.revision, denied)
            )
        assertEquals(denied, assertIs<AppResult.Failed>(f.receive()).failure)
        assertEquals(0, f.request.reads)
    }

    @Test
    fun mappedStorageFailureWaitsForAnotherBatchWithoutRestartingObservation() = runTest {
        val f = IncomingBatchFixture()
        f.request.base.failures["write"] = f.request.base.directory.storageFailure
        val results = mutableListOf<AppResult<Boolean>>()
        val observing = backgroundScope.launch { f.receiver.observe(f.runtime).toList(results) }
        runCurrent()
        assertIs<AppResult.Failed>(results.single())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, results.size)
        assertEquals(1, f.request.writes)
        f.request.base.failures.clear()
        f.responses.value =
            AppResult.Success(f.batch.copy(previousPosition = "next", nextPosition = "later"))
        runCurrent()
        assertEquals(AppResult.Success(true), results.last())
        assertEquals(1, f.starts)
        assertEquals(2, f.request.writes)
        observing.cancelAndJoin()
        assertEquals(1, f.stops)
    }

    @Test
    fun concurrentCollectorsWaitWithoutSubscribingAndCancellationReleasesOwner() = runTest {
        val f = IncomingBatchFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.request.base.hooks["write"] = {
            entered.complete(Unit)
            release.await()
        }
        val first = async { f.receive() }
        entered.await()
        val second = async { f.receive() }
        runCurrent()
        assertEquals(1, f.starts)
        assertEquals(1, f.request.writes)
        second.cancelAndJoin()
        release.complete(Unit)
        assertEquals(AppResult.Success(true), first.await())
        assertEquals(1, f.stops)
        assertEquals(AppResult.Success(false), f.receive())
        assertEquals(2, f.starts)
    }

    @Test
    fun cancellationBeforeOrAfterCommitNeverResubmitsTheCapturedBatch() = runTest {
        for (stage in listOf("write", "committed")) {
            val f = IncomingBatchFixture()
            val entered = CompletableDeferred<Unit>()
            var cancelled = false
            f.request.base.hooks[stage] = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            val collecting = async { f.receive() }
            entered.await()
            collecting.cancelAndJoin()
            assertTrue(cancelled)
            assertEquals(1, f.starts)
            assertEquals(1, f.stops)
            assertEquals(0, f.request.schedules)
            f.request.base.hooks.clear()
            assertEquals(AppResult.Success(stage == "write"), f.receive())
            assertEquals(if (stage == "write") 2 else 1, f.request.writes)
        }
    }

    @Test
    fun hungPersistenceIsBoundedAndADurableWakeSurvivesSchedulerFailure() = runTest {
        val blocked = IncomingBatchFixture()
        blocked.request.base.hooks["write"] = { awaitCancellation() }
        val started = currentTime
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(blocked.receive()).failure.kind,
        )
        assertEquals(5_000L, currentTime - started)
        assertNull(blocked.request.snapshot)
        for (hang in listOf(false, true)) {
            val f = IncomingBatchFixture()
            f.request.base.hooks["schedule"] = { if (hang) awaitCancellation() }
            f.request.base.failures["schedule"] = f.request.base.directory.storageFailure
            assertEquals(AppResult.Success(true), f.receive())
            assertNotNull(f.request.wake)
            assertNotNull(f.request.snapshot)
            assertEquals(1, f.request.writes)
        }
    }

    @Test
    fun mutableBatchRoomListIsCapturedBeforeLocalAdmissionSuspends() = runTest {
        val f = IncomingBatchFixture()
        val rooms = f.batch.rooms.toMutableList()
        f.responses.value = AppResult.Success(f.batch.copy(rooms = rooms))
        f.request.base.directory.beforeAdmission = { rooms.clear() }
        assertEquals(AppResult.Success(true), f.receive())
        assertEquals(1, f.request.snapshot?.demand?.physicalRooms?.size)
    }
}

private class IncomingBatchFixture {
    val request = SpaceIncomingRequestFixture()
    val runtime = request.provider.runtime
    val source = CollaborationSpaceIncomingSource("1".repeat(64), "@sender:matrix.test", "DEVICE")
    var installed: CollaborationSpaceIncomingSource? = source
    val batch =
        CollaborationSpaceSyncBatch(
            runtime,
            source,
            "old",
            "next",
            listOf(
                CollaborationSpaceIncomingRoomHint(
                    "!room:matrix.test",
                    "\$head",
                    CollaborationSpaceIncomingMembership.Joined,
                    false,
                )
            ),
            false,
        )
    val responses =
        MutableStateFlow<AppResult<CollaborationSpaceSyncBatch>>(AppResult.Success(batch))
    var starts = 0
    var stops = 0
    private val sync =
        object : CollaborationSpaceSyncRepository {
            override suspend fun synchronize(
                runtime: CollaborationSpaceRuntimeReady
            ): AppResult<Unit> = error("Incoming observation must never start or retry sync")

            override fun incomingSource(
                runtime: CollaborationSpaceRuntimeReady
            ): CollaborationSpaceIncomingSource? {
                assertEquals(this@IncomingBatchFixture.runtime, runtime)
                return installed
            }

            override fun observeBatches(
                runtime: CollaborationSpaceRuntimeReady
            ): Flow<AppResult<CollaborationSpaceSyncBatch>> = flow {
                assertEquals(this@IncomingBatchFixture.runtime, runtime)
                starts++
                try {
                    emitAll(responses)
                } finally {
                    stops++
                }
            }
        }
    val receiver =
        RetainCollaborationSpaceSyncBatches(
            sync,
            request.demands,
            request.base.directory.directory,
            request.base.native.setup.sessions,
            request.base.native.server,
            AppBuild(AppPlatform.Android, 5, "test", null),
            object : SyncScheduleRepository {
                override suspend fun request(key: String): AppResult<Unit> {
                    assertEquals(CollaborationSpaceIncomingWake.WORK_KEY, key)
                    request.schedules++
                    return request.base.read("schedule") { Unit }
                }
            },
        )

    suspend fun receive() = receiver.observe(runtime).first()

    fun id(value: Int) = request.provider.queue.id(value)
}
