package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ResolveCollaborationSpaceOriginalPhysicalBindingTest {
    @Test
    fun originalRoomIndexWorksWithoutAPublishedAliasOrLiveCredentials() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val original = assertNotNull(assertNotNull(f.base.retained).original)
        f.base.retained = null
        f.originalCanonical = original
        f.originalIndex = original
        f.base.native.setup.now += 365.days
        assertEquals(queued, f.resolve())
        assertEquals(
            CollaborationSpaceIncomingTarget.Original(f.originalPosition),
            f.request.snapshot?.demand?.rooms?.single()?.target,
        )
        assertTrue(f.remoteReads.isEmpty())
        assertTrue(f.originalMetadataWrites.isEmpty())
        assertEquals(0, f.request.schedules)
    }

    @Test
    fun legacyAliasWithAnotherRegisteredPhysicalDeviceWaitsWithoutInventingASource() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val other = f.source.copy(storeId = "2".repeat(64), matrixDeviceId = "OTHER_DEVICE")
        val captured = f.physical.copy(sourceId = other.storeId)
        f.installed = other
        val old = assertNotNull(f.request.snapshot)
        f.request.snapshot =
            old.copy(
                demand =
                    old.demand.copy(
                        sources = old.demand.sources.map { it.copy(source = other) },
                        physicalRooms = listOf(captured),
                    )
            )
        val before = f.request.snapshot
        assertEquals(waiting, f.resolve(captured))
        assertEquals(before, f.request.snapshot)
        assertTrue(f.remoteReads.isEmpty() && f.originalMetadataWrites.isEmpty())
        assertEquals(0, f.request.writes)
    }

    @Test
    fun legacyAliasFromAnotherCompanyKeepsItsPhysicalRequest() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val old = assertNotNull(f.base.retained)
        val original = assertNotNull(old.original)
        val foreign = f.originalPosition.copy(companyId = f.id(980))
        f.base.retained =
            old.copy(
                room =
                    old.room.copy(
                        original = assertNotNull(old.room.original).copy(position = foreign)
                    ),
                original =
                    original.copy(conversation = original.conversation.copy(position = foreign)),
            )
        assertTrue(
            validCollaborationSpaceRoomBinding(f.request.access, assertNotNull(f.base.retained))
        )
        val before = f.request.snapshot
        assertEquals(waiting, f.resolve())
        assertEquals(before, f.request.snapshot)
        assertTrue(f.remoteReads.isEmpty() && f.originalMetadataWrites.isEmpty())
    }

    @Test
    fun spaceRegistrationCannotTurnLocalOrRemoteLegacyAliasesIntoOriginalWork() = runTest {
        for (remote in listOf(false, true)) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            if (remote) f.missingLocal()
            f.registration =
                f.registered.copy(
                    command =
                        f.registered.command.copy(
                            origin = CollaborationSpaceDeviceOrigin.SPACE,
                            legacy = null,
                        )
                )
            assertTrue(
                validCollaborationSpaceRegistrationRecord(
                    f.request.access,
                    assertNotNull(f.registration),
                )
            )
            val before = f.request.snapshot
            assertEquals(waiting, f.resolve())
            assertEquals(before, f.request.snapshot)
            assertTrue(f.originalReads.isEmpty() && f.originalMetadataWrites.isEmpty())
            assertEquals(
                if (remote) listOf("physical", "conversation") else emptyList(),
                f.remoteReads,
            )
        }
    }

    @Test
    fun unsupportedSourceDoesNotSearchArbitraryCompaniesAfterPublishedAbsence() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.base.retained = null
        f.registration =
            f.registered.copy(
                command =
                    f.registered.command.copy(
                        origin = CollaborationSpaceDeviceOrigin.SPACE,
                        legacy = null,
                    )
            )
        assertEquals(waiting, f.resolve())
        assertEquals(listOf("physical"), f.remoteReads)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun authoritativePublishedAbsenceResolvesOriginalMetadataAndItsTypedTarget() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.base.retained = null
        f.originalPhysicalFailure = null
        val before = assertNotNull(f.request.snapshot).demand
        val wake = f.request.wake
        assertEquals(queued, f.resolve())
        val after = assertNotNull(f.request.snapshot).demand
        val task = after.rooms.single()
        assertEquals(CollaborationSpaceIncomingTarget.Original(f.originalPosition), task.target)
        assertEquals(f.physical.generation, task.generation)
        assertEquals(f.physical.position, task.position)
        assertEquals(f.physical.headEventId, task.headEventId)
        assertEquals(f.physical.sourceId, task.sourceId)
        assertEquals(before.sources, after.sources)
        assertEquals(before.rescanGeneration, after.rescanGeneration)
        assertEquals(wake, f.request.wake)
        assertEquals(listOf("physical", "original-physical"), f.remoteReads)
        assertEquals(1, f.originalMetadataWrites.size)
        assertEquals(f.originalCanonical, f.originalIndex)
        assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
        assertEquals(1, f.request.writes)
    }

    @Test
    fun networkErrorsOtherAbsencesAndDenialsNeverTriggerTheOriginalFallback() = runTest {
        for (mode in 0..3) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.base.retained = null
            f.physicalFailure =
                when (mode) {
                    0 -> f.missing.copy(kind = FailureKind.Unavailable)
                    1 -> f.missing.copy(kind = FailureKind.Timeout)
                    2 -> f.missing.copy(code = "other_not_found")
                    else -> f.base.directory.denied
                }
            assertIs<AppResult.Failed>(f.resolve())
            assertEquals(listOf("physical"), f.remoteReads)
            assertTrue(f.originalMetadataWrites.isEmpty())
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun originalAbsenceRetainsDemandAndEachInvocationMakesOnlyOneFallback() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.base.retained = null
        val before = f.request.snapshot
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.BindingMissing),
            f.resolve(),
        )
        assertEquals(before, f.request.snapshot)
        assertEquals(listOf("physical", "original-physical"), f.remoteReads)
        assertEquals(0, f.request.writes)
        f.originalPhysicalFailure = null
        assertEquals(queued, f.resolve())
        assertEquals(
            listOf("physical", "original-physical", "physical", "original-physical"),
            f.remoteReads,
        )
        assertEquals(1, f.originalMetadataWrites.size)
    }

    @Test
    fun malformedOriginalOwnerCompanyServerOrRoomCannotBeRetained() = runTest {
        for (mode in 0..4) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.base.retained = null
            f.originalPhysicalFailure = null
            val original = f.remoteOriginal
            f.remoteOriginal =
                when (mode) {
                    0 -> original.copy(owner = original.owner.copy(spaceId = f.id(980)))
                    1 ->
                        original.copy(
                            conversation =
                                original.conversation.copy(
                                    position = f.originalPosition.copy(companyId = f.id(980))
                                )
                        )
                    2 -> original.copy(room = null)
                    3 ->
                        original.copy(
                            room = assertNotNull(original.room).copy(serverName = "foreign.test")
                        )
                    else ->
                        original.copy(
                            room =
                                assertNotNull(original.room).copy(roomId = "!foreign:example.test")
                        )
                }
            assertIs<AppResult.Failed>(f.resolve(), mode.toString())
            assertTrue(f.originalMetadataWrites.isEmpty())
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun conflictingOrPartialOriginalIndexesAreNeverTreatedAsMissing() = runTest {
        for (mode in 0..3) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            val original = assertNotNull(assertNotNull(f.base.retained).original)
            val conflict =
                original.copy(
                    room = original.room.copy(boundAt = original.room.boundAt + 1.seconds)
                )
            when (mode) {
                0 -> {
                    f.originalCanonical = conflict
                    f.originalIndex = conflict
                }
                1 -> f.originalCanonical = original
                2 -> f.originalIndex = original
                else -> {
                    f.originalCanonical = original
                    f.originalIndex = conflict
                }
            }
            val before = f.request.snapshot
            assertIs<AppResult.Failed>(f.resolve())
            assertEquals(before, f.request.snapshot)
            assertTrue(f.originalMetadataWrites.isEmpty() && f.remoteReads.isEmpty())
        }
    }

    @Test
    fun uncommittedOriginalMetadataCannotConvertRegardlessOfItsReply() = runTest {
        for (remote in listOf(false, true)) for (reply in listOf(false, true)) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            if (remote) {
                f.base.retained = null
                f.originalPhysicalFailure = null
            }
            f.originalMetadataCommit = false
            f.originalMetadataReply = reply
            val before = f.request.snapshot
            assertIs<AppResult.Failed>(f.resolve())
            assertEquals(before, f.request.snapshot)
            assertEquals(1, f.originalMetadataWrites.size)
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun lostOriginalMetadataRepliesAreConfirmedLocallyBeforeConversion() = runTest {
        for (remote in listOf(false, true)) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            if (remote) {
                f.base.retained = null
                f.originalPhysicalFailure = null
            }
            f.loseOriginalMetadataReply = true
            assertEquals(queued, f.resolve())
            assertEquals(1, f.originalMetadataWrites.size)
            assertEquals(1, f.request.writes)
            assertEquals(f.originalCanonical, f.originalIndex)
        }
    }

    @Test
    fun unreadableOriginalConfirmationKeepsItsCommitForALocalRetry() = runTest {
        for (stage in listOf("original-metadata", "original-index")) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.base.retained = null
            f.originalPhysicalFailure = null
            f.base.hooks["original-metadata-committed"] = {
                f.base.failures[stage] = f.base.directory.storageFailure
            }
            assertIs<AppResult.Failed>(f.resolve())
            assertEquals(0, f.request.writes)
            assertNotNull(f.originalCanonical)
            val reads = f.remoteReads.toList()
            f.base.hooks.clear()
            f.base.failures.clear()
            assertEquals(queued, f.resolve())
            assertEquals(reads, f.remoteReads)
            assertEquals(1, f.originalMetadataWrites.size)
        }
    }

    @Test
    fun fullRegistrationReplacementFencesLocalAndRemoteStorageStages() = runTest {
        for (remote in listOf(false, true)) for (stage in originalStages) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            if (remote) {
                f.base.retained = null
                f.originalPhysicalFailure = null
            }
            f.base.hooks[stage] = {
                f.registration = f.registered.copy(revision = f.registered.revision + 1)
            }
            assertIs<AppResult.Failed>(f.resolve(), "$remote:$stage")
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun currentDenialWinsAfterEveryOriginalReadAndCommit() = runTest {
        for (remote in listOf(false, true)) for (stage in originalStages) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            if (remote) {
                f.base.retained = null
                f.originalPhysicalFailure = null
            }
            f.base.hooks[stage] = { f.base.directory.deny() }
            f.base.failures[stage] = f.base.directory.storageFailure
            assertEquals(
                f.base.directory.denied,
                assertIs<AppResult.Failed>(f.resolve()).failure,
                "$remote:$stage",
            )
            assertNotNull(f.request.wake)
        }
    }

    @Test
    fun originalFallbackDenialSurvivesFailedRestrictionCleanup() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.base.retained = null
        f.originalPhysicalFailure = f.base.directory.denied
        f.base.directory.afterRestrict = {
            f.base.directory.gate.value = AppResult.Failed(f.base.directory.storageFailure)
            AppResult.Failed(f.base.directory.storageFailure)
        }
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.resolve()).failure)
        assertEquals(1, f.base.directory.restrictions)
        assertTrue(f.originalMetadataWrites.isEmpty())
    }

    @Test
    fun aPublishedAbsenceAfterSourceOrRegistrationReplacementCannotStartFallback() = runTest {
        for (registration in listOf(false, true)) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.base.retained = null
            f.base.hooks["remote-physical"] = {
                if (registration) f.registration = f.registered.copy(revision = 3)
                else f.installed = null
            }
            assertIs<AppResult.Failed>(f.resolve())
            assertEquals(listOf("physical"), f.remoteReads)
        }
    }

    @Test
    fun originalAcquisitionAndConversionShareTheRemainingLiveDeadline() = runTest {
        for (stage in listOf("remote-original-physical", "original-metadata-committed", "write")) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.base.retained = null
            f.originalPhysicalFailure = null
            f.base.hooks[stage] = { f.base.native.setup.now += 365.days }
            assertIs<AppResult.Failed>(f.resolve(), stage)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun publishedLookupAndOriginalFallbackHaveOneCombinedTimeout() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.base.retained = null
        f.originalPhysicalFailure = null
        f.base.hooks["remote-physical"] = { delay(4_000) }
        var stopped = false
        f.base.hooks["remote-original-physical"] = {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val before = testScheduler.currentTime
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.resolve()).failure.kind)
        assertEquals(15_000L, testScheduler.currentTime - before)
        assertTrue(stopped)
        assertEquals(listOf("physical", "original-physical"), f.remoteReads)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun cancellationStopsOriginalIoWithoutSchedulingAReplacementAttempt() = runTest {
        for (stage in
            listOf(
                "remote-original-physical",
                "original-metadata-write",
                "original-metadata-committed",
            )) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.base.retained = null
            f.originalPhysicalFailure = null
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            f.base.hooks[stage] = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            val job = async { f.resolve() }
            entered.await()
            job.cancelAndJoin()
            assertTrue(stopped)
            assertEquals(0, f.request.writes)
            assertEquals(0, f.request.schedules)
            assertNotNull(f.request.wake)
        }
    }

    @Test
    fun newerPhysicalGenerationSurvivesOriginalRetentionAndReusesItLocally() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.base.retained = null
        f.originalPhysicalFailure = null
        val newer = f.physical.copy(generation = f.id(980), headEventId = "$" + "later")
        f.base.hooks["original-metadata-committed"] = {
            val old = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                old.copy(
                    generation = f.id(981),
                    demand = old.demand.copy(physicalRooms = listOf(newer)),
                )
        }
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Obsolete),
            f.resolve(),
        )
        assertEquals(0, f.request.writes)
        assertEquals(listOf(newer), f.request.snapshot?.demand?.physicalRooms)
        val reads = f.remoteReads.toList()
        f.base.hooks.clear()
        assertEquals(queued, f.resolve(newer))
        assertEquals(reads, f.remoteReads)
        assertEquals(1, f.originalMetadataWrites.size)
    }

    @Test
    fun oldUnsourcedPublishedTaskIsPreservedWithoutRelabelingItsProgress() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        val old = assertNotNull(f.request.snapshot)
        val published =
            CollaborationSpaceIncomingRoomDemand(
                f.base.model.conversation.id,
                f.id(980),
                1,
                null,
                CollaborationSpaceIncomingPhase.Pending,
            )
        f.request.snapshot =
            old.copy(demand = old.demand.copy(nextPosition = 2, rooms = listOf(published)))
        assertEquals(queued, f.resolve())
        val tasks = assertNotNull(f.request.snapshot).demand.rooms
        assertEquals(published, tasks.single { it.generation == published.generation })
        assertEquals(
            CollaborationSpaceIncomingTarget.Original(f.originalPosition),
            tasks.single { it.generation == f.physical.generation }.target,
        )
        assertEquals(2, tasks.size)
    }

    private val queued = AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued)
    private val waiting =
        AppResult.Success(CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource)
    private val originalStages =
        listOf(
            "original-metadata",
            "original-index",
            "original-metadata-write",
            "original-metadata-committed",
            "write",
            "committed",
            "readback",
        )
}
