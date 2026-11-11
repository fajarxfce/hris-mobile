package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ResolveCollaborationSpaceIncomingAliasTest {
    @Test
    fun aRetainedLegacyAliasSchedulesCanonicalCompanyWorkWithoutNetwork() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        val before = assertNotNull(f.request.snapshot).demand
        val wake = f.request.wake
        f.base.native.setup.now += 365.days
        assertEquals(queued, f.useCase(f.runtime, captured))
        val task = assertNotNull(f.request.snapshot).demand.rooms.single()
        assertEquals(CollaborationSpaceIncomingTarget.Original(f.originalPosition), task.target)
        assertNotEquals(captured.generation, task.generation)
        assertEquals(CollaborationSpaceIncomingPhase.Latest, task.phase)
        assertEquals(f.source.storeId, task.sourceId)
        assertEquals(before.sources, f.request.snapshot?.demand?.sources)
        assertEquals(before.rescanGeneration, f.request.snapshot?.demand?.rescanGeneration)
        assertEquals(wake, f.request.wake)
        assertEquals(1, f.request.writes)
        assertEquals(1, f.originalMetadataWrites.size)
        assertEquals(f.originalCanonical, f.originalIndex)
        assertTrue(f.remoteReads.isEmpty())
        assertEquals(0, f.request.schedules)
    }

    @Test
    fun aSourceBoundAliasKeepsItsActualRegisteredPhysicalStore() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias(true)
        assertEquals(queued, f.useCase(f.runtime, captured))
        assertEquals(captured.sourceId, f.request.snapshot?.demand?.rooms?.single()?.sourceId)
    }

    @Test
    fun aTrueSpaceTaskDoesNotNeedAnOriginalRegistrationOrSource() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        val binding = assertNotNull(f.base.retained)
        f.base.retained =
            binding.copy(
                room =
                    binding.room.copy(origin = CollaborationSpaceRoomOrigin.SPACE, original = null),
                original = null,
            )
        f.registration = null
        f.installed = null
        val before = f.request.snapshot
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Published),
            f.useCase(f.runtime, captured),
        )
        assertEquals(before, f.request.snapshot)
        assertFalse("registration" in f.base.calls)
        assertTrue(f.originalMetadataWrites.isEmpty() && f.remoteReads.isEmpty())
    }

    @Test
    fun anAbsentAliasRetainsItsTaskWithoutStartingRemoteDiscovery() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        f.base.retained = null
        val before = f.request.snapshot
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.BindingMissing),
            f.useCase(f.runtime, captured),
        )
        assertEquals(before, f.request.snapshot)
        assertTrue(f.remoteReads.isEmpty())
    }

    @Test
    fun aNewerProducerGenerationStopsTheOldAliasBeforeMetadataReads() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        val old = assertNotNull(f.request.snapshot)
        val newer = captured.copy(generation = f.id(740), headEventId = "\$later")
        f.request.snapshot = old.copy(demand = old.demand.copy(rooms = listOf(newer)))
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Obsolete),
            f.useCase(f.runtime, captured),
        )
        assertEquals(listOf(newer), f.request.snapshot?.demand?.rooms)
        assertFalse("binding-confirm" in f.base.calls)
    }

    @Test
    fun originalTargetsCannotEnterThePublishedAliasPath() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured =
            f.logicalAlias(true)
                .copy(target = CollaborationSpaceIncomingTarget.Original(f.originalPosition))
        assertIs<AppResult.Failed>(f.useCase(f.runtime, captured))
        assertTrue(f.base.calls.isEmpty())
    }

    @Test
    fun aNullSourceAliasWaitsForGenuineProgressWithoutInventingACursor() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        val old = assertNotNull(f.request.snapshot)
        f.request.snapshot = old.copy(demand = old.demand.copy(sources = emptyList()))
        val before = f.request.snapshot
        assertEquals(waiting, f.useCase(f.runtime, captured))
        assertEquals(before, f.request.snapshot)
        assertEquals(0, f.request.writes)
        assertTrue(f.originalMetadataWrites.isEmpty())
    }

    @Test
    fun anUnavailableInstalledSourceKeepsTheAlias() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        f.installed = null
        val before = f.request.snapshot
        assertEquals(waiting, f.useCase(f.runtime, captured))
        assertEquals(before, f.request.snapshot)
    }

    @Test
    fun unsupportedSpaceRegistrationDoesNotInventOriginalCoordinates() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        f.registration =
            f.registered.copy(
                command =
                    f.registered.command.copy(
                        origin = CollaborationSpaceDeviceOrigin.SPACE,
                        legacy = null,
                    )
            )
        assertEquals(waiting, f.useCase(f.runtime, captured))
        assertTrue(f.originalMetadataWrites.isEmpty())
        assertEquals(0, f.request.writes)
    }

    @Test
    fun anAliasFromAnotherCompanyRemainsUnconverted() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        val binding = assertNotNull(f.base.retained)
        val original = assertNotNull(binding.original)
        val foreign = f.originalPosition.copy(companyId = f.id(745))
        f.base.retained =
            binding.copy(
                room =
                    binding.room.copy(
                        original = assertNotNull(binding.room.original).copy(position = foreign)
                    ),
                original =
                    original.copy(conversation = original.conversation.copy(position = foreign)),
            )
        assertTrue(
            validCollaborationSpaceRoomBinding(f.request.access, assertNotNull(f.base.retained))
        )
        assertEquals(waiting, f.useCase(f.runtime, captured))
        assertTrue(f.originalMetadataWrites.isEmpty())
    }

    @Test
    fun existingUnfinishedOriginalRetainsBothHeadsAndDoesNotRewriteMetadata() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        val old = assertNotNull(f.request.snapshot)
        val original =
            CollaborationSpaceIncomingRoomDemand(
                CollaborationSpaceIncomingTarget.Original(f.originalPosition),
                f.id(746),
                1,
                null,
                CollaborationSpaceIncomingPhase.Pending,
                sourceId = f.source.storeId,
            )
        f.request.snapshot =
            old.copy(demand = old.demand.copy(nextPosition = 2, rooms = listOf(captured, original)))
        val before = f.request.snapshot
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.WaitingForPrevious),
            f.useCase(f.runtime, captured),
        )
        assertEquals(before, f.request.snapshot)
        assertTrue(f.originalMetadataWrites.isEmpty())
    }

    @Test
    fun aPartialImmutableIndexPreventsQueueConversion() = runTest {
        for (canonical in listOf(false, true)) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            val captured = f.logicalAlias()
            if (canonical) f.originalCanonical = assertNotNull(f.base.retained).original
            else f.originalIndex = assertNotNull(f.base.retained).original
            assertIs<AppResult.Failed>(f.useCase(f.runtime, captured))
            assertEquals(0, f.request.writes)
            assertTrue(f.originalMetadataWrites.isEmpty())
        }
    }

    @Test
    fun exactRetainedIndexesNeedNoRepeatedMetadataWrite() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        f.originalCanonical = assertNotNull(f.base.retained).original
        f.originalIndex = f.originalCanonical
        assertEquals(queued, f.useCase(f.runtime, captured))
        assertTrue(f.originalMetadataWrites.isEmpty())
    }

    @Test
    fun metadataWriteReplyAloneCannotConfirmRetention() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        f.originalMetadataCommit = false
        assertIs<AppResult.Failed>(f.useCase(f.runtime, captured))
        assertEquals(0, f.request.writes)
    }

    @Test
    fun aLostMetadataReplyUsesExactReadbackWithoutReplayingTheWrite() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        f.loseOriginalMetadataReply = true
        assertEquals(queued, f.useCase(f.runtime, captured))
        assertEquals(1, f.originalMetadataWrites.size)
        assertEquals(1, f.request.writes)
    }

    @Test
    fun aLostQueueReplyIsConfirmedByTheNewExactOriginalTask() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        f.request.responseLoss = true
        assertEquals(queued, f.useCase(f.runtime, captured))
        assertEquals(1, f.request.writes)
        assertIs<CollaborationSpaceIncomingTarget.Original>(
            f.request.snapshot?.demand?.rooms?.single()?.target
        )
    }

    @Test
    fun aKnownCasMissRetainsTheAliasAsContention() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        f.request.commit = false
        val before = f.request.snapshot
        assertEquals(collaborationSpaceIncomingQueueContention(), f.useCase(f.runtime, captured))
        assertEquals(before, f.request.snapshot)
        assertEquals(1, f.request.writes)
    }

    @Test
    fun producerWorkInsertedBeforeTheCasPreservesTheOriginalAlias() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        f.base.hooks["write"] = {
            val old = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                old.copy(
                    generation = f.id(750),
                    demand = old.demand.copy(rescanGeneration = f.id(751)),
                )
        }
        assertEquals(collaborationSpaceIncomingQueueContention(), f.useCase(f.runtime, captured))
        assertEquals(listOf(captured), f.request.snapshot?.demand?.rooms)
        assertEquals(f.id(751), f.request.snapshot?.demand?.rescanGeneration)
    }

    @Test
    fun aNewProducerHeadBeforeTheCasIsNeverOverwritten() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        val newer = captured.copy(generation = f.id(752), headEventId = "\$newer")
        f.base.hooks["write"] = {
            val old = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                old.copy(generation = f.id(753), demand = old.demand.copy(rooms = listOf(newer)))
        }
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Obsolete),
            f.useCase(f.runtime, captured),
        )
        assertEquals(listOf(newer), f.request.snapshot?.demand?.rooms)
    }

    @Test
    fun changedAliasMetadataStopsTheQueueWriteAfterOriginalRetention() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val captured = f.logicalAlias()
        f.base.hooks["original-metadata-committed"] = { f.base.retained = null }
        assertIs<AppResult.Failed>(f.useCase(f.runtime, captured))
        assertEquals(0, f.request.writes)
        assertEquals(listOf(captured), f.request.snapshot?.demand?.rooms)
    }

    @Test
    fun currentDenialWinsAcrossMetadataAndQueueSuspensions() = runTest {
        for (stage in stages) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            val captured = f.logicalAlias()
            f.base.hooks[stage] = { f.base.directory.deny() }
            f.base.failures[stage] = f.base.directory.storageFailure
            assertEquals(
                f.base.directory.denied,
                assertIs<AppResult.Failed>(f.useCase(f.runtime, captured)).failure,
                stage,
            )
            assertNotNull(f.request.wake)
            assertTrue(f.remoteReads.isEmpty())
        }
    }

    @Test
    fun fullRegistrationReplacementIsCheckedAfterEveryStorageStage() = runTest {
        for (stage in stages) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            val captured = f.logicalAlias()
            f.base.hooks[stage] = { f.registration = f.registered.copy(revision = 3) }
            assertIs<AppResult.Failed>(f.useCase(f.runtime, captured), stage)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun installedSourceRetirementStopsLaterMetadataAndQueueWork() = runTest {
        for (stage in stages) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            val captured = f.logicalAlias()
            f.base.hooks[stage] = { f.installed = null }
            assertIs<AppResult.Failed>(f.useCase(f.runtime, captured), stage)
            assertNotNull(f.request.wake)
        }
    }

    @Test
    fun oneFiveSecondDeadlineIncludesMetadataAndQueueConfirmation() = runTest {
        for (stage in listOf("original-metadata", "original-metadata-write", "write", "readback")) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            val captured = f.logicalAlias()
            var stopped = false
            f.base.hooks[stage] = {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            val started = testScheduler.currentTime
            assertEquals(
                FailureKind.Timeout,
                assertIs<AppResult.Failed>(f.useCase(f.runtime, captured)).failure.kind,
            )
            assertEquals(5_000L, testScheduler.currentTime - started)
            assertTrue(stopped)
            assertNotNull(f.request.wake)
        }
    }

    @Test
    fun parentCancellationIsPropagatedAndDoesNotReplayStorage() = runTest {
        for (stage in stages) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            val captured = f.logicalAlias()
            f.base.hooks[stage] = { throw CancellationException("Fixture cancellation") }
            assertFailsWith<CancellationException> { f.useCase(f.runtime, captured) }
            assertTrue(f.originalMetadataWrites.size <= 1)
            assertTrue(f.request.writes <= 1)
            assertNotNull(f.request.wake)
        }
    }

    private val stages =
        listOf(
            "original-metadata",
            "original-index",
            "original-metadata-write",
            "original-metadata-committed",
            "write",
            "committed",
            "readback",
        )
    private val queued = AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued)
    private val waiting =
        AppResult.Success(CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource)
}
