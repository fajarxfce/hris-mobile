package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationHistoryProgress
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AcquireCollaborationSpaceOriginalProviderHistoryTest {
    @Test
    fun oneOriginalPageRetainsItsPhysicalOwnerAndCompanyCursorWithoutTransportAcquisition() =
        runTest {
            val f = SpaceOriginalProviderHistoryFixture()
            val queued = f.base.snapshot
            val result = success(f)
            assertEquals(f.page.physical, f.saved.single())
            assertEquals(f.traversal, result.room.traversal)
            assertNotEquals(f.runtime.deviceId, result.room.traversal.deviceId)
            assertNull(result.progress.provider)
            assertEquals(2, result.references)
            assertEquals(queued, f.base.snapshot)
            assertEquals(listOf<Pair<String?, String?>>(null to null), f.requests)
            assertTrue(f.sourceReads.isEmpty())
            assertEquals(0, f.base.setup.inserts)
            assertTrue(f.base.setup.sent.isEmpty())
        }

    @Test
    fun pendingPhaseAndMalformedCapturedRoomFailBeforeAnyRepositoryRead() = runTest {
        for (pending in listOf(false, true)) {
            val f = SpaceOriginalProviderHistoryFixture()
            if (pending) f.phase(CollaborationSpaceIncomingPhase.Pending)
            else f.request = f.request.copy(traversal = f.traversal.copy(roomId = "bad"))
            assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertTrue(f.calls.isEmpty())
            assertEquals(0, f.base.sourceReads)
        }
    }

    @Test
    fun ownedLocalMetadataDoesNotGrantExpiredNativeContentAccess() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.base.native.setup.now += 365.days
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.requests.isEmpty())
        assertNull(f.progress)
    }

    @Test
    fun missingSourceAndUnregisteredDeviceCannotStartHistoryReads() = runTest {
        for (source in listOf(false, true)) {
            val f = SpaceOriginalProviderHistoryFixture()
            if (source) f.base.installed = null else f.base.registration = null
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.requests.isEmpty())
            assertTrue(f.saved.isEmpty())
            assertFalse("progress" in f.calls)
        }
    }

    @Test
    fun groupReceiptCannotReplaceTheRegisteredOriginalHistoryDevice() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.request = f.request.copy(traversal = f.traversal.copy(deviceId = f.runtime.deviceId))
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.requests.isEmpty())
        assertFalse("progress" in f.calls)
    }

    @Test
    fun foreignMetadataServerIsRejectedBeforeAnyProviderProbe() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        val foreign =
            f.base.room.copy(serverName = "other.example", roomId = "!original:other.example")
        f.request =
            f.request.copy(
                conversation = f.request.conversation.copy(room = foreign),
                traversal = f.traversal.copy(roomId = foreign.roomId),
            )
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.requests.isEmpty())
        assertFalse("metadata" in f.calls)
    }

    @Test
    fun savedProviderPositionResumesWithoutRestartingTheWave() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        val first = success(f)
        f.phase(CollaborationSpaceIncomingPhase.Provider)
        f.page =
            f.page.copy(
                physical =
                    f.page.physical.copy(requestedPosition = "next", start = "next", next = null)
            )
        val second = success(f)
        assertEquals(first.progress, second.previous)
        assertEquals(2L, second.progress.references)
        assertTrue(second.progress.exhausted)
        assertEquals(listOf<Pair<String?, String?>>(null to null, "next" to null), f.requests)
        assertEquals(first.progress.incoming?.head, second.progress.incoming?.head)
    }

    @Test
    fun alreadyExhaustedSavedPositionMakesNoProviderOrStorageWrite() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.phase(CollaborationSpaceIncomingPhase.Provider)
        val checkpoint =
            CollaborationHistoryProgress(
                f.base.id(100),
                0,
                null,
                0,
                CollaborationIncomingHistoryPosition("head", null),
            )
        f.progress = checkpoint
        val result = success(f)
        assertEquals(checkpoint, result.previous)
        assertEquals(checkpoint, result.progress)
        assertEquals(0, result.references)
        assertTrue(f.requests.isEmpty())
        assertTrue(f.saved.isEmpty())
    }

    @Test
    fun latestWaveUsesFinishedHeadAsBoundaryAndKeepsPendingSeek() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        val pending =
            CollaborationIncomingHistoryPosition(
                "old-head",
                null,
                pendingAfter = "pending-cursor",
                pendingThrough = 2,
                pendingRetryRequired = true,
            )
        f.progress = CollaborationHistoryProgress(f.base.id(100), 1, null, 2, pending)
        assertTrue(validCollaborationHistoryProgress(assertNotNull(f.progress)))
        f.page = f.page.copy(physical = f.page.physical.copy(requestedUntilPosition = "old-head"))
        val result = success(f)
        assertEquals(listOf<Pair<String?, String?>>(null to "old-head"), f.requests)
        assertEquals("old-head", result.progress.incoming?.until)
        assertEquals("pending-cursor", result.progress.incoming?.pendingAfter)
        assertEquals(2L, result.progress.incoming?.pendingThrough)
        assertTrue(assertNotNull(result.progress.incoming).pendingRetryRequired)
        assertEquals(4L, result.progress.references)
    }

    @Test
    fun groupProviderCheckpointCannotBecomeAnOriginalCompanyCursor() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.progress =
            CollaborationHistoryProgress(
                f.base.id(100),
                0,
                "next",
                0,
                CollaborationIncomingHistoryPosition("head", null),
                CollaborationHistoryProviderIdentity(f.base.setup.company, f.traversal.deviceId),
            )
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.requests.isEmpty())
        assertTrue(f.saved.isEmpty())
    }

    @Test
    fun physicalPageFromAnotherCompanyDeviceParentOrRoomIsNeverRelabeled() = runTest {
        val seed = SpaceOriginalProviderHistoryFixture()
        val page = seed.page.physical
        for (bad in
            listOf(
                page.copy(owner = page.owner.copy(companyId = seed.base.id(110))),
                page.copy(owner = page.owner.copy(nativeSessionId = seed.base.id(110))),
                page.copy(owner = page.owner.copy(sessionId = "replaced")),
                page.copy(deviceId = seed.runtime.deviceId),
                page.copy(roomId = "!other:matrix.hris.internal"),
            )) {
            val f = SpaceOriginalProviderHistoryFixture()
            f.page = f.page.copy(physical = bad)
            assertIs<AppResult.Failed>(f.run())
            assertEquals(1, f.requests.size)
            assertTrue(f.saved.isEmpty())
        }
    }

    @Test
    fun oversizedProviderPageIsRejectedBeforeStorage() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.page =
            f.page.copy(
                physical =
                    f.page.physical.copy(
                        events =
                            List(21) {
                                f.page.physical.events.first().copy(eventId = "\$large-$it")
                            }
                    )
            )
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.saved.isEmpty())
    }

    @Test
    fun exactSourceRegistrationOrDemandReplacementRejectsLateProviderSuccess() = runTest {
        for (change in
            listOf("source", "registration", "demand", "metadata", "runtime", "parent")) {
            val f = SpaceOriginalProviderHistoryFixture()
            f.hooks["page"] = {
                when (change) {
                    "source" -> f.base.installed = f.base.source.copy(matrixDeviceId = "OTHER")
                    "registration" ->
                        f.base.registration = assertNotNull(f.base.registration).copy(revision = 3)
                    "demand" ->
                        f.base.demand(
                            assertNotNull(f.base.snapshot)
                                .demand
                                .copy(
                                    rooms =
                                        listOf(f.base.captured.copy(generation = f.base.id(111)))
                                )
                        )
                    "metadata" -> f.base.retained = null
                    "runtime" -> f.open = false
                    else -> {
                        val session = assertNotNull(f.base.native.setup.session)
                        f.base.native.setup.session =
                            session.copy(binding = session.binding.copy(serverId = f.base.id(111)))
                    }
                }
            }
            assertIs<AppResult.Failed>(f.run(), change)
            assertEquals(1, f.requests.size)
            assertTrue(f.saved.isEmpty(), change)
        }
    }

    @Test
    fun unrelatedSourcePositionAdvanceDoesNotInvalidateTheSameCapturedTask() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.hooks["page"] = {
            val snapshot = assertNotNull(f.base.snapshot)
            f.base.snapshot =
                snapshot.copy(
                    generation = f.base.id(112),
                    demand =
                        snapshot.demand.copy(
                            sources =
                                snapshot.demand.sources.map { it.copy(position = "position-2") }
                        ),
                )
        }
        success(f)
        assertEquals(1, f.requests.size)
        assertEquals(1, f.saved.size)
    }

    @Test
    fun denialAfterProviderReadWinsEvenWhenRestrictionPersistenceFails() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.failures["page"] = f.base.directory.denied
        f.base.directory.afterRestrict = { AppResult.Failed(f.base.directory.storageFailure) }
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertTrue(f.saved.isEmpty())
        assertEquals(1, f.closes)
    }

    @Test
    fun denialAfterAtomicCommitDoesNotRollBackOwnedHistoryOrPublishSuccess() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.hooks["committed"] = { f.base.directory.deny() }
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertNotNull(f.progress)
        assertEquals(2, f.references.size)
        assertEquals(1, f.saved.size)
        assertEquals(1, f.closes)
    }

    @Test
    fun lostStorageReplyIsConfirmedFromExactCheckpointAndIndexedSources() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.stageResponse = AppResult.Failed(f.base.directory.storageFailure)
        val result = success(f)
        assertEquals(f.progress, result.progress)
        assertEquals(f.page.physical.events.map { it.eventId }, f.sourceReads)
        assertEquals(1, f.requests.size)
        assertEquals(1, f.saved.size)
    }

    @Test
    fun lostReplyWithoutCommittedProgressRemainsTheOriginalStorageFailure() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.commit = false
        f.stageResponse = AppResult.Failed(f.base.directory.storageFailure)
        assertEquals(f.base.directory.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertNull(f.progress)
        assertTrue(f.sourceReads.isEmpty())
        assertEquals(1, f.requests.size)
    }

    @Test
    fun missingOrDifferentStoredEventCannotConfirmAnUncertainReply() = runTest {
        for (missing in listOf(false, true)) {
            val f = SpaceOriginalProviderHistoryFixture()
            f.stageResponse = AppResult.Failed(f.base.directory.storageFailure)
            f.hooks["committed"] = {
                val event = f.page.physical.events.first()
                f.sourceResponses[event.eventId] =
                    CollaborationHistorySource(
                        f.progress,
                        if (missing) null
                        else
                            CollaborationHistoryReference(
                                0,
                                event.copy(sender = "@different:example.test"),
                            ),
                    )
            }
            assertEquals(
                f.base.directory.storageFailure,
                assertIs<AppResult.Failed>(f.run()).failure,
            )
            assertEquals(1, f.sourceReads.size)
            assertEquals(1, f.requests.size)
        }
    }

    @Test
    fun uncertainEmptyEofIsConfirmedWithoutInventingAReferenceRead() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.page = f.page.copy(physical = f.page.physical.copy(next = null, events = emptyList()))
        f.stageResponse = AppResult.Failed(f.base.directory.storageFailure)
        val result = success(f)
        assertTrue(result.progress.exhausted)
        assertEquals(0L, result.progress.references)
        assertTrue(f.sourceReads.isEmpty())
        assertEquals(1, f.requests.size)
    }

    @Test
    fun falseCasIsNotPromotedToAStorageAcknowledgement() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.commit = false
        f.stageResponse = AppResult.Success(false)
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertNull(f.progress)
        assertTrue(f.sourceReads.isEmpty())
    }

    @Test
    fun changedRetainedCheckpointCannotAcknowledgeTheReceivedPage() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.hooks["committed"] = { f.progress = assertNotNull(f.progress).copy(next = "unrelated") }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(1, f.requests.size)
    }

    @Test
    fun stalledProviderHasOneDeadlineAndNoRetry() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        f.hooks["page"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(1, f.requests.size)
        assertTrue(f.saved.isEmpty())
    }

    @Test
    fun monotonicExpiryAndBackwardsWallTimeRejectLatePages() = runTest {
        for (backwards in listOf(false, true)) {
            val f = SpaceOriginalProviderHistoryFixture()
            f.hooks["page"] = {
                if (backwards) f.base.native.setup.now -= 1.seconds
                else f.base.native.time += 16.seconds
            }
            assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertTrue(f.saved.isEmpty())
        }
    }

    @Test
    fun cancellationCannotCommitANativeResultReturnedAfterTheCallerStopped() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.hooks["page"] = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val job = async { f.run() }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(f.saved.isEmpty())
    }

    @Test
    fun twoCapturesCannotBothConfirmTheSameProviderTransition() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        val both = CompletableDeferred<Unit>()
        var pages = 0
        f.hooks["page"] = {
            if (++pages == 2) both.complete(Unit)
            both.await()
        }
        val first = async { f.run() }
        val second = async { f.run() }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it is AppResult.Success })
        assertEquals(1, results.count { it is AppResult.Failed })
        assertEquals(2, f.requests.size)
        assertEquals(2, f.references.size)
    }

    @Test
    fun providerOwnedEventListIsCapturedBeforeAdmissionSuspendsAgain() = runTest {
        val f = SpaceOriginalProviderHistoryFixture()
        val events = f.page.physical.events.toMutableList()
        f.page = f.page.copy(physical = f.page.physical.copy(events = events))
        f.hooks["registration"] = { if (f.requests.isNotEmpty()) events.clear() }
        val result = success(f)
        assertEquals(2, result.references)
        assertEquals(2, f.saved.single().events.size)
        assertTrue(events.isEmpty())
    }

    private suspend fun success(f: SpaceOriginalProviderHistoryFixture) =
        assertIs<AppResult.Success<CollaborationSpaceOriginalProviderHistoryAcquisition>>(f.run())
            .value
}
