package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.isCollaborationSpaceIncomingQueueContention
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RetainCollaborationSpaceRoomInventoryTest {
    @Test
    fun oneExactOriginalPageCommitsItsWorkSourceProgressAndWakeTogether() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Complete), f.run())
        assertEquals(1, f.enumerations)
        assertEquals(1, f.writes)
        assertEquals(3, f.reads)
        assertNotNull(f.wake)
        val retained = assertNotNull(f.snapshot).demand
        assertEquals(listOf("!room:test"), retained.physicalRooms.map { it.roomId })
        assertEquals(
            CollaborationSpaceIncomingSourceProgress(f.base.source, "before"),
            retained.sources.single(),
        )
        assertNull(retained.physicalRooms.single().headEventId)
        assertTrue(f.base.setup.sent.isEmpty())
        assertEquals(0, f.base.setup.inserts)
    }

    @Test
    fun obsoleteDemandPerformsNoNativeReadOrWrite() = runTest {
        for (absent in listOf(false, true)) {
            val f = SpaceRoomInventoryRetentionFixture()
            if (absent) f.snapshot = null
            else f.progress(f.captured.copy(inventoryGeneration = f.base.id(702)))
            assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Obsolete), f.run())
            assertEquals(0, f.enumerations)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun futureRetryDoesNotReadTheStoreOrEnumerateAgain() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        val now = f.base.native.clock.now().toEpochMilliseconds()
        f.captured =
            f.captured.copy(inventoryRetry = CollaborationSpaceIncomingRetry(now, now + 30_000, 1))
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Deferred), f.run())
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.base.sourceReads)
    }

    @Test
    fun aBackwardClockAdjustmentDoesNotHideRetainedInventory() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        val attempted = f.base.native.clock.now().toEpochMilliseconds() + 1_000
        f.captured =
            f.captured.copy(
                inventoryRetry = CollaborationSpaceIncomingRetry(attempted, attempted + 30_000, 1)
            )
        f.progress(f.captured)
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Complete), f.run())
        assertEquals(1, f.enumerations)
        assertEquals(1, f.writes)
    }

    @Test
    fun invalidCaptureFailsBeforeLocalOrNativeIo() = runTest {
        for (invalid in listOf("bad", null)) {
            val f = SpaceRoomInventoryRetentionFixture()
            f.captured = f.captured.copy(inventoryGeneration = invalid)
            assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test
    fun missingInstalledOriginalDoesNotCreateANewClientOrReadDemand() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.base.installed = null
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun unconfirmedOrWrongDeviceRegistrationCannotEnumerate() = runTest {
        for (missing in listOf(false, true)) {
            val f = SpaceRoomInventoryRetentionFixture()
            f.base.registration =
                if (missing) null
                else
                    assertNotNull(f.base.registration)
                        .copy(
                            outcome =
                                CollaborationSpaceRegistrationOutcome.Registered(
                                    f.base.setup.receipt.copy(id = f.base.id(22))
                                )
                        )
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test
    fun oldCredentialsAndSelectedCompanyChangesDoNotHideOwnedInventory() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Complete), f.run())
        assertEquals(1, f.enumerations)
    }

    @Test
    fun exactRegistrationReplacementWhileReadingRejectsTheLatePage() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.hooks["inventory"] = {
            f.base.registration = assertNotNull(f.base.registration).copy(revision = 3)
        }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun retiringTheOriginalAtEveryStageRejectsLateResults() = runTest {
        for (stage in listOf("demand1", "inventory", "demand2", "write", "committed", "demand3")) {
            val f = SpaceRoomInventoryRetentionFixture()
            f.hooks[stage] = { f.base.installed = null }
            assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertTrue(f.writes <= 1)
        }
    }

    @Test
    fun malformedOrForeignRuntimePageCannotBeSaved() = runTest {
        for (foreign in listOf(false, true)) {
            val f = SpaceRoomInventoryRetentionFixture()
            f.page =
                if (foreign) f.page.copy(runtime = f.runtime.copy(operationId = f.base.id(23)))
                else
                    f.page.copy(
                        rooms =
                            List(17) {
                                CollaborationSpaceInventoryRoom(
                                    "!$it:test",
                                    CollaborationSpaceInventoryMembership.Joined,
                                )
                            }
                    )
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun ordinarySyncAdvancementWhileReadingKeepsItsNewestPosition() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.hooks["inventory"] = { f.progress(f.captured.copy(position = "new-continuous-position")) }
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Complete), f.run())
        assertEquals("new-continuous-position", f.snapshot?.demand?.sources?.single()?.position)
    }

    @Test
    fun newLossDuringNativeReadKeepsTheNewGenerationAndPerformsNoWrite() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        val newer = f.captured.copy(position = "new", inventoryGeneration = f.base.id(702))
        f.hooks["inventory"] = { f.progress(newer) }
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Obsolete), f.run())
        assertEquals(newer, f.snapshot?.demand?.sources?.single())
        assertEquals(0, f.writes)
    }

    @Test
    fun anUnchangedExistingPhysicalTurnWaitsWithoutAWriteOrWake() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        val old =
            CollaborationSpaceIncomingPhysicalRoomDemand(
                f.base.source.storeId,
                "!room:test",
                f.base.id(88),
                0,
                "\$known",
                CollaborationSpaceIncomingMembership.Left,
                false,
            )
        f.snapshot =
            assertNotNull(f.snapshot)
                .copy(
                    demand =
                        assertNotNull(f.snapshot)
                            .demand
                            .copy(nextPosition = 1, physicalRooms = listOf(old))
                )
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.WaitingForRoom), f.run())
        assertEquals(0, f.writes)
        assertNull(f.wake)
        assertEquals(listOf(old), f.snapshot?.demand?.physicalRooms)
    }

    @Test
    fun joinedPhysicalRequirementAndCursorCommitTogetherAcrossLostReply() = runTest {
        for (lostReply in listOf(false, true)) {
            val f = SpaceRoomInventoryRetentionFixture()
            val old =
                CollaborationSpaceIncomingPhysicalRoomDemand(
                    f.base.source.storeId,
                    "!room:test",
                    f.base.id(88),
                    0,
                    "\$known",
                    CollaborationSpaceIncomingMembership.Joined,
                    false,
                    CollaborationSpaceIncomingRetry(1_000, 31_000, 1),
                )
            val before = assertNotNull(f.snapshot)
            f.snapshot =
                before.copy(
                    demand = before.demand.copy(nextPosition = 1, physicalRooms = listOf(old))
                )
            f.replyLost = lostReply
            assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Complete), f.run())
            val retained = assertNotNull(f.snapshot).demand
            val next = retained.physicalRooms.single()
            assertNotEquals(old.generation, next.generation)
            assertEquals(
                old.copy(generation = next.generation, headEventId = null, limited = true),
                next,
            )
            assertNull(retained.sources.single().inventoryGeneration)
            assertEquals(1, f.writes)
            assertNotNull(f.wake)
            assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Obsolete), f.run())
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun lostCommitReplyUsesDurableProgressWithoutRepeatingTheMutation() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.replyLost = true
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Complete), f.run())
        assertEquals(1, f.writes)
        assertNotNull(f.wake)
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Obsolete), f.run())
        assertEquals(1, f.enumerations)
    }

    @Test
    fun laterContinuousPositionAndNewUnrelatedWorkSurviveReadback() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.replyLost = true
        f.hooks["committed"] = {
            val saved = assertNotNull(f.snapshot)
            f.snapshot =
                saved.copy(
                    generation = f.base.id(950),
                    demand =
                        saved.demand.copy(
                            rescanGeneration = f.base.id(951),
                            sources = saved.demand.sources.map { it.copy(position = "later") },
                        ),
                )
        }
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Complete), f.run())
        assertEquals("later", f.snapshot?.demand?.sources?.single()?.position)
        assertEquals(f.base.id(951), f.snapshot?.demand?.rescanGeneration)
        assertEquals(1, f.writes)
    }

    @Test
    fun failedCasNeverOverwritesACompetingProducerOrLoopsInline() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        val newer = f.captured.copy(position = "new", inventoryGeneration = f.base.id(702))
        f.hooks["write"] = { f.progress(newer) }
        assertTrue(
            isCollaborationSpaceIncomingQueueContention(assertIs<AppResult.Failed>(f.run()).failure)
        )
        assertEquals(newer, f.snapshot?.demand?.sources?.single())
        assertEquals(1, f.writes)
        assertEquals(1, f.enumerations)
        assertNull(f.wake)
    }

    @Test
    fun unconfirmedWriteAndNewLossCannotFalselyConfirmOldCoverage() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.replyLost = true
        val newer = f.captured.copy(position = "new", inventoryGeneration = f.base.id(702))
        f.hooks["committed"] = { f.progress(newer) }
        assertEquals(f.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(newer, f.snapshot?.demand?.sources?.single())
        assertEquals(1, f.writes)
    }

    @Test
    fun storageFailureWithoutACommitCannotClaimProgress() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.commit = false
        f.replyLost = true
        val before = f.snapshot
        assertEquals(f.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(before, f.snapshot)
        assertEquals(1, f.writes)
    }

    @Test
    fun rawFailureKeepsItsClassificationAndNeverWritesOrRestartsTransport() = runTest {
        for (kind in listOf(FailureKind.Timeout, FailureKind.Unavailable, FailureKind.Storage)) {
            val f = SpaceRoomInventoryRetentionFixture()
            val failure = Failure(kind, "fixture")
            f.failures["inventory"] = failure
            assertEquals(failure, assertIs<AppResult.Failed>(f.run()).failure)
            assertEquals(1, f.enumerations)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun hungNativeReadIsBoundedAndCancellationDoesNotRetry() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.hooks["inventory"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(5_000L, currentTime)
        assertEquals(1, f.enumerations)
        assertEquals(0, f.writes)
    }

    @Test
    fun cancellationAfterCommitRetainsWorkAndWakeForTheNextWorker() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.hooks["committed"] = { throw CancellationException("lost waiter") }
        assertFailsWith<CancellationException> { f.run() }
        assertNotNull(f.wake)
        assertEquals(1, f.snapshot?.demand?.physicalRooms?.size)
        assertEquals(1, f.writes)
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Obsolete), f.run())
        assertEquals(1, f.enumerations)
    }

    @Test
    fun confirmedDenialWinsOverFailedReadsAtEverySuspendingBoundary() = runTest {
        for (stage in listOf("demand1", "inventory", "demand2", "write", "committed", "demand3")) {
            val f = SpaceRoomInventoryRetentionFixture()
            f.hooks[stage] = { f.base.directory.deny() }
            f.failures[stage] = f.storageFailure
            assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.run()).failure)
            assertTrue(f.writes <= 1)
        }
    }

    @Test
    fun malformedRuntimeFailsBeforeAnyAccountOrStoreRead() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        for (runtime in
            listOf(f.runtime.copy(deviceId = "bad"), f.runtime.copy(operationId = "bad"))) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.retain(runtime, f.captured)).failure.kind,
            )
        }
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.base.sourceReads)
    }

    @Test
    fun accountAndNativeSessionReplacementFenceEachSuspendingStage() = runTest {
        for (stage in listOf("demand1", "inventory", "demand2", "write", "committed", "demand3")) {
            for (native in listOf(false, true)) {
                val f = SpaceRoomInventoryRetentionFixture()
                f.hooks[stage] = {
                    val old = assertNotNull(f.base.native.setup.session)
                    f.base.native.setup.session =
                        if (native) old.copy(binding = old.binding.copy(serverId = f.base.id(26)))
                        else null
                }
                assertIs<AppResult.Failed>(f.run(), "$stage/$native")
                assertTrue(f.writes <= 1)
            }
        }
    }

    @Test
    fun rawRoomListIsCapturedBeforePostReadAdmissionSuspends() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        val mutable = f.page.rooms.toMutableList()
        f.page = f.page.copy(rooms = mutable)
        f.base.hooks["registration3"] = { mutable.clear() }
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Complete), f.run())
        assertTrue(mutable.isEmpty())
        assertEquals(listOf("!room:test"), f.snapshot?.demand?.physicalRooms?.map { it.roomId })
    }

    @Test
    fun knownDenialSurvivesRuntimeRetirementAfterTheFailedOperation() = runTest {
        for (stage in listOf("inventory", "write")) {
            val f = SpaceRoomInventoryRetentionFixture()
            val denied = Failure(FailureKind.AccessDenied, "fixture denied")
            f.hooks[stage] = { f.base.installed = null }
            f.failures[stage] = denied
            assertEquals(denied, assertIs<AppResult.Failed>(f.run()).failure)
            assertTrue(f.writes <= 1)
        }
    }
}
