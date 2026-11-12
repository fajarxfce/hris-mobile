package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ResolveCollaborationSpaceIncomingBindingTest {
    @Test
    fun lateOriginalMetadataStillGetsPrimaryWorkAfterHistorySweepCompleted() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val previous = assertNotNull(f.request.snapshot)
        val history =
            CollaborationSpaceHistoryRecovery(
                f.id(790),
                revision = 1,
                unresolved = true,
                complete = true,
            )
        f.request.snapshot = previous.copy(demand = previous.demand.copy(historyRecovery = history))
        f.base.hooks["write"] = {
            assertEquals(assertNotNull(f.base.retained).original, f.originalCanonical)
            assertEquals(f.originalCanonical, f.originalIndex)
            assertEquals(history, assertNotNull(f.request.snapshot).demand.historyRecovery)
        }
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
            f.resolve(),
        )
        val retained = assertNotNull(f.request.snapshot).demand
        assertEquals(history, retained.historyRecovery)
        assertEquals(
            CollaborationSpaceIncomingTarget.Original(f.originalPosition),
            retained.rooms.single().target,
        )
        assertEquals(CollaborationSpaceIncomingPhase.Latest, retained.rooms.single().phase)
        assertEquals(f.physical.generation, retained.rooms.single().generation)
        assertTrue(retained.physicalRooms.isEmpty())
        assertEquals(1, f.originalMetadataWrites.size)
    }

    @Test
    fun latePublishedPhysicalHintStillGetsPrimaryWorkAfterHistorySweepCompleted() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val binding = assertNotNull(f.base.retained)
        f.base.retained =
            binding.copy(
                room =
                    binding.room.copy(origin = CollaborationSpaceRoomOrigin.SPACE, original = null),
                original = null,
            )
        val previous = assertNotNull(f.request.snapshot)
        val history = CollaborationSpaceHistoryRecovery(f.id(791), revision = 1, complete = true)
        f.request.snapshot = previous.copy(demand = previous.demand.copy(historyRecovery = history))
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
            f.resolve(),
        )
        val retained = assertNotNull(f.request.snapshot).demand
        assertEquals(history, retained.historyRecovery)
        assertEquals(CollaborationSpaceIncomingPhase.Latest, retained.rooms.single().phase)
        assertEquals(f.physical.generation, retained.rooms.single().generation)
        assertTrue(retained.physicalRooms.isEmpty())
        assertTrue(f.originalMetadataWrites.isEmpty())
    }

    @Test
    fun retainedLegacyBindingConvertsLocallyWithoutAnotherWakeOrTransport() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val before = assertNotNull(f.request.snapshot).demand
        val wake = f.request.wake
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
            f.resolve(),
        )
        val after = assertNotNull(f.request.snapshot).demand
        assertEquals(before.sources, after.sources)
        assertEquals(before.rescanGeneration, after.rescanGeneration)
        assertEquals(wake, f.request.wake)
        assertEquals(f.physical.generation, after.rooms.single().generation)
        assertEquals(f.source.storeId, after.rooms.single().sourceId)
        assertEquals(CollaborationSpaceIncomingPhase.Latest, after.rooms.single().phase)
        assertTrue(after.physicalRooms.isEmpty())
        assertEquals(1, f.request.writes)
        assertEquals(0, f.request.schedules)
        assertEquals(
            CollaborationSpaceIncomingTarget.Original(f.originalPosition),
            after.rooms.single().target,
        )
        assertEquals(listOf(requireNotNull(f.base.retained).original), f.originalMetadataWrites)
        assertTrue(f.remoteReads.isEmpty())
        assertEquals(
            listOf("demand", "binding", "write", "committed", "readback"),
            f.base.calls.filter {
                it in setOf("demand", "binding", "write", "committed", "readback")
            },
        )
    }

    @Test
    fun credentialAgeDoesNotBlockOwnedLocalConversion() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.base.native.setup.now += 365.days
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
            f.resolve(),
        )
        assertEquals(1, f.request.writes)
    }

    @Test
    fun publishedSpaceRoomNeedsNoOriginalCompanyMetadata() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val previous = assertNotNull(f.base.retained)
        f.base.retained =
            previous.copy(
                room =
                    previous.room.copy(
                        origin = CollaborationSpaceRoomOrigin.SPACE,
                        original = null,
                    ),
                original = null,
            )
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
            f.resolve(),
        )
        assertEquals(1, f.request.writes)
    }

    @Test
    fun missingBindingRetainsPhysicalDemandWithoutInventingOrAcquiringMetadata() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val before = f.request.snapshot
        f.base.retained = null
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.BindingMissing),
            f.resolve(),
        )
        assertEquals(before, f.request.snapshot)
        assertEquals(0, f.request.writes)
        assertEquals(listOf("demand", "registration", "binding"), f.base.calls.take(3))
        assertEquals(listOf("physical", "original-physical"), f.remoteReads)
        assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
        assertEquals(1, f.base.calls.count { it == "remote-physical" })
    }

    @Test
    fun absentOrSupersededCaptureStopsBeforeRegistrationAndBindingReads() = runTest {
        for (mode in 0..2) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            val previous = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                when (mode) {
                    0 -> null
                    1 -> previous.copy(demand = previous.demand.copy(physicalRooms = emptyList()))
                    else ->
                        previous.copy(
                            demand =
                                previous.demand.copy(
                                    physicalRooms = listOf(f.physical.copy(generation = f.id(900)))
                                )
                        )
                }
            assertEquals(
                AppResult.Success(CollaborationSpaceIncomingBindingResolution.Obsolete),
                f.resolve(),
            )
            assertEquals(listOf("demand"), f.base.calls)
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun unfinishedLogicalWorkKeepsItsPhaseAndThePhysicalRequest() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val before = assertNotNull(f.request.snapshot)
        val logical =
            CollaborationSpaceIncomingRoomDemand(
                CollaborationSpaceIncomingTarget.Original(f.originalPosition),
                f.id(800),
                1,
                "\$old",
                CollaborationSpaceIncomingPhase.Pending,
                sourceId = f.source.storeId,
            )
        f.request.snapshot =
            before.copy(demand = before.demand.copy(nextPosition = 2, rooms = listOf(logical)))
        val captured = f.request.snapshot
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.WaitingForPrevious),
            f.resolve(),
        )
        assertEquals(captured, f.request.snapshot)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun lostReplyConfirmsConversionWhileProducerAdvancesCursorAndAddsAnotherHint() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.request.responseLoss = true
        f.base.hooks["committed"] = {
            val retained = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                retained.copy(
                    generation = f.id(901),
                    demand =
                        retained.demand.copy(
                            nextPosition = 2,
                            sources =
                                retained.demand.sources.map {
                                    it.copy(position = "later", inventoryGeneration = f.id(903))
                                },
                            physicalRooms =
                                listOf(
                                    f.physical.copy(
                                        generation = f.id(902),
                                        position = 1,
                                        headEventId = "\$newer",
                                    )
                                ),
                        ),
                )
        }
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
            f.resolve(),
        )
        val after = assertNotNull(f.request.snapshot).demand
        assertEquals("later", after.sources.single().position)
        assertEquals("\$newer", after.physicalRooms.single().headEventId)
        assertEquals(f.physical.generation, after.rooms.single().generation)
        assertEquals(1, f.request.writes)
    }

    @Test
    fun producerChangeBeforeCasIsPreservedWithoutRetryingTheWrite() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.base.hooks["write"] = {
            val retained = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                retained.copy(
                    generation = f.id(901),
                    demand =
                        retained.demand.copy(
                            sources = retained.demand.sources.map { it.copy(position = "later") }
                        ),
                )
        }
        val failure = assertIs<AppResult.Failed>(f.resolve()).failure
        assertEquals(FailureKind.Unavailable, failure.kind)
        assertEquals("client.collaboration_incoming_queue_busy", failure.code)
        assertEquals(1L, failure.retryAfterSeconds)
        assertEquals(1, f.request.writes)
        assertEquals("later", f.request.snapshot?.demand?.sources?.single()?.position)
        assertEquals(listOf(f.physical), f.request.snapshot?.demand?.physicalRooms)
    }

    @Test
    fun aKnownCasMissWithANewerPhysicalGenerationLeavesThatGenerationForItsOwnTurn() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.base.hooks["write"] = {
            val retained = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                retained.copy(
                    generation = f.id(910),
                    demand =
                        retained.demand.copy(
                            physicalRooms =
                                listOf(
                                    f.physical.copy(generation = f.id(911), headEventId = "\$later")
                                )
                        ),
                )
        }
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Obsolete),
            f.resolve(),
        )
        assertEquals(f.id(911), f.request.snapshot?.demand?.physicalRooms?.single()?.generation)
        assertEquals(1, f.request.writes)
    }

    @Test
    fun uncertainWriteWithNoExactConvertedTaskKeepsItsStorageFailure() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.base.hooks["write"] = {
            val retained = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                retained.copy(
                    generation = f.id(910),
                    demand =
                        retained.demand.copy(
                            physicalRooms = listOf(f.physical.copy(generation = f.id(911)))
                        ),
                )
        }
        f.base.failures["write"] = f.base.directory.storageFailure
        assertEquals(
            f.base.directory.storageFailure,
            assertIs<AppResult.Failed>(f.resolve()).failure,
        )
        assertEquals(1, f.request.writes)
    }

    @Test
    fun uncommittedOrUnreadableConversionCannotClaimSuccess() = runTest {
        for (mode in 0..2) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            if (mode == 0) f.request.commit = false
            if (mode == 1) f.base.failures["write"] = f.base.directory.storageFailure
            if (mode == 2) f.base.failures["readback"] = f.base.directory.storageFailure
            assertIs<AppResult.Failed>(f.resolve())
            assertEquals(1, f.request.writes)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun confirmedDenialWinsOverLateStorageFailureAtEveryStage() = runTest {
        for (stage in stages) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            val denied = f.base.directory.denied
            f.base.hooks[stage] = {
                f.base.directory.gate.value =
                    AppResult.Success(
                        CollaborationSpaceDirectoryAdmission(f.base.directory.revision, denied)
                    )
            }
            f.base.failures[stage] = f.base.directory.storageFailure
            assertEquals(denied, assertIs<AppResult.Failed>(f.resolve()).failure, stage)
            assertEquals(
                if (stage in listOf("write", "committed", "readback")) 1 else 0,
                f.request.writes,
                stage,
            )
        }
    }

    @Test
    fun confirmedDenialStillWinsWhenRuntimeHasAlreadyClosed() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.installed = null
        val denied = f.base.directory.denied
        f.base.directory.gate.value =
            AppResult.Success(
                CollaborationSpaceDirectoryAdmission(f.base.directory.revision, denied)
            )
        assertEquals(denied, assertIs<AppResult.Failed>(f.resolve()).failure)
        assertEquals(0, f.request.reads)
    }

    @Test
    fun originalSourceReplacementAtEveryPendingStageRejectsTheOldResult() = runTest {
        for (stage in stages) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            f.base.hooks[stage] = { f.installed = f.source.copy(storeId = "2".repeat(64)) }
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.resolve()).failure.kind,
                stage,
            )
        }
    }

    @Test
    fun accountAndNativeParentReplacementFenceEveryPendingStage() = runTest {
        for (stage in stages) for (native in listOf(false, true)) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            val setup = f.base.native.setup
            f.base.hooks[stage] = {
                val session = assertNotNull(setup.session)
                setup.session =
                    if (native) session.copy(binding = session.binding.copy(serverId = f.id(999)))
                    else session.copy(user = session.user.copy(id = f.id(999)))
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.resolve()).failure.kind,
                stage,
            )
        }
    }

    @Test
    fun wrongRegistrationCannotChooseBindingServerOrRoom() = runTest {
        for (mode in 0..6) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            val r = f.registered
            f.registration =
                when (mode) {
                    0 -> null
                    1 ->
                        r.copy(
                            outcome =
                                CollaborationSpaceRegistrationOutcome.Registered(
                                    CollaborationSetupReceipt(f.id(999), 0)
                                )
                        )
                    2 ->
                        r.copy(
                            command =
                                r.command.copy(
                                    binding = r.command.binding.copy(nativeSessionId = f.id(999))
                                )
                        )
                    3 ->
                        r.copy(
                            command =
                                r.command.copy(
                                    provider =
                                        r.command.provider.copy(matrixUserId = "@different:test")
                                )
                        )
                    4 -> r.copy(outcome = CollaborationSpaceRegistrationOutcome.Unconfirmed())
                    5 ->
                        r.copy(
                            command =
                                r.command.copy(
                                    binding = r.command.binding.copy(accountId = f.id(999))
                                )
                        )
                    else ->
                        r.copy(command = r.command.copy(platform = CollaborationNativePlatform.IOS))
                }
            assertIs<AppResult.Failed>(f.resolve(), mode.toString())
            assertEquals(listOf("demand", "registration"), f.base.calls)
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun mismatchedOrIncompleteLocalBindingDoesNotConsumeDemand() = runTest {
        for (mode in 0..3) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            val b = assertNotNull(f.base.retained)
            f.base.retained =
                when (mode) {
                    0 -> b.copy(room = b.room.copy(serverName = "other.test"))
                    1 -> b.copy(room = b.room.copy(roomId = "!other:test"))
                    2 -> b.copy(original = null)
                    else -> b.copy(conversation = b.conversation.copy(spaceId = f.id(999)))
                }
            assertIs<AppResult.Failed>(f.resolve(), mode.toString())
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun wrongSourceMalformedCaptureAndChangedSameGenerationCannotReachBinding() = runTest {
        for (mode in 0..3) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            val captured =
                when (mode) {
                    0 -> f.physical.copy(sourceId = "2".repeat(64))
                    1 -> f.physical.copy(roomId = "")
                    2 -> f.physical.copy(headEventId = "\$different")
                    else -> f.physical
                }
            if (mode == 3) f.installed = null
            assertIs<AppResult.Failed>(f.resolve(captured))
            assertFalse("binding" in f.base.calls)
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun cancellationBeforeAndAfterCommitLeavesOneRecoverableOriginalTurn() = runTest {
        for (stage in listOf("write", "committed")) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            f.base.hooks[stage] = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            val work = async { f.resolve() }
            entered.await()
            work.cancelAndJoin()
            assertTrue(stopped)
            assertEquals(1, f.request.writes)
            assertNotNull(f.request.wake)
            val saved = assertNotNull(f.request.snapshot).demand
            assertEquals(1, saved.rooms.size + saved.physicalRooms.size)
            assertEquals(if (stage == "write") 1 else 0, saved.physicalRooms.size)
        }
    }

    @Test
    fun pendingStorageIsBoundedAndDoesNotStartBackgroundRecovery() = runTest {
        for (stage in listOf("demand", "registration", "binding", "write", "readback")) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            var stopped = false
            f.base.hooks[stage] = {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            val started = currentTime
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.resolve()).failure.kind)
            assertEquals(5_000L, currentTime - started)
            assertTrue(stopped)
            assertEquals(0, f.request.schedules)
            assertNotNull(f.request.wake)
        }
    }

    @Test
    fun invalidReadbackCannotMasqueradeAsAnExactConversion() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.request.responseLoss = true
        f.base.hooks["committed"] = {
            val retained = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                retained.copy(
                    demand =
                        retained.demand.copy(
                            rooms = retained.demand.rooms.map { it.copy(sourceId = null) }
                        )
                )
        }
        assertIs<AppResult.Failed>(f.resolve())
        assertEquals(1, f.request.writes)
    }

    private val stages =
        listOf("demand", "registration", "binding", "write", "committed", "readback")
}
