package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CompleteCollaborationSpaceOriginalIncomingRoomTest {
    @Test
    fun drainedOriginalContentRetiresOnlyTheCapturedTaskWithoutProviderWork() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        val before = assertNotNull(f.base.snapshot).demand
        assertEquals(AppResult.Success(true), f.run())
        assertNull(f.retainedTask())
        assertEquals(before.copy(rooms = emptyList()), assertNotNull(f.base.snapshot).demand)
        assertEquals(1, f.transition.writes)
        assertTrue(f.provider.requests.isEmpty())
        assertTrue(f.provider.saved.isEmpty())
    }

    @Test
    fun missingProgressOrProviderContinuationCannotRetireTheTask() = runTest {
        for (missing in listOf(true, false)) {
            val f = SpaceOriginalQueueCompletionFixture()
            f.provider.progress =
                if (missing) null else assertNotNull(f.provider.progress).copy(next = "older")
            assertEquals(AppResult.Success(false), f.run())
            assertEquals(f.pending.room.captured, f.retainedTask())
            assertEquals(0, f.transition.writes)
        }
    }

    @Test
    fun undecryptedReferenceKeepsTaskWithoutRestartingOrAcquiringHistory() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        f.pending.pending = listOf(f.pending.head(resolved = false))
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(0, f.transition.writes)
        assertTrue(f.provider.sourceReads.isEmpty())
        assertTrue(f.provider.requests.isEmpty())
    }

    @Test
    fun emptyReferencesDoNotRetireAnUnfinishedFiniteSeekOrItsRetry() = runTest {
        for (retry in listOf(false, true)) {
            val f = SpaceOriginalQueueCompletionFixture()
            val progress = assertNotNull(f.provider.progress)
            f.provider.progress =
                progress.copy(
                    incoming =
                        assertNotNull(progress.incoming)
                            .copy(
                                pendingAfter = "seek",
                                pendingThrough = 2,
                                pendingRetryRequired = retry,
                            )
                )
            assertEquals(AppResult.Success(false), f.run())
            assertEquals(0, f.transition.writes)
            assertTrue(f.provider.sourceReads.isEmpty())
        }
    }

    @Test
    fun missingRequestedHeadKeepsProviderRecoverySeparate() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        f.provider.references.clear()
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(0, f.transition.writes)
        assertTrue(f.provider.requests.isEmpty())
    }

    @Test
    fun unknownStartupHeadStillRequiresRealDrainedExhaustion() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        f.pending.capture(f.pending.room.captured.copy(headEventId = null))
        assertEquals(AppResult.Success(true), f.run())
        assertNull(f.retainedTask())
        assertTrue(f.provider.sourceReads.isEmpty())
    }

    @Test
    fun wrongHeadIdentityResolutionOrSequenceNeverAcknowledgesTheTask() = runTest {
        for (changed in listOf("id", "resolved", "sequence", "sender")) {
            val f = SpaceOriginalQueueCompletionFixture()
            val head = f.pending.head()
            f.provider.references[head.event.eventId] =
                when (changed) {
                    "id" -> head.copy(event = head.event.copy(eventId = "\$other"))
                    "resolved" -> head.copy(resolved = false)
                    "sequence" -> head.copy(sequence = 2)
                    else -> head.copy(event = head.event.copy(sender = "invalid"))
                }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.transition.writes)
        }
    }

    @Test
    fun changedHeadCheckpointCannotConfirmAnOlderRead() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        val head = f.pending.head()
        f.provider.sourceResponses[head.event.eventId] =
            CollaborationHistorySource(assertNotNull(f.provider.progress).copy(revision = 1), head)
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.transition.writes)
    }

    @Test
    fun newerProgressBeforeQueueCommitRetainsTheCapture() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        f.provider.hooks["progress"] = {
            f.provider.progress =
                assertNotNull(f.provider.progress).copy(revision = 1, references = 3)
        }
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(0, f.transition.writes)
    }

    @Test
    fun missingAndNewGenerationTasksAreObsoleteWithoutHistoryReads() = runTest {
        for (missing in listOf(true, false)) {
            val f = SpaceOriginalQueueCompletionFixture()
            val captured = f.pending.room
            if (missing)
                f.base.demand(assertNotNull(f.base.snapshot).demand.copy(rooms = emptyList()))
            else f.pending.capture(captured.captured.copy(generation = f.base.id(999)))
            assertEquals(AppResult.Success(true), f.complete(f.base.runtime, captured))
            assertFalse("pending" in f.provider.calls)
            assertEquals(0, f.transition.writes)
        }
    }

    @Test
    fun changedSameGenerationTaskAndGroupReceiptAreNotOriginalOwnership() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        val captured = f.pending.room
        f.pending.capture(captured.captured.copy(phase = CollaborationSpaceIncomingPhase.Provider))
        assertIs<AppResult.Failed>(f.complete(f.base.runtime, captured))
        assertIs<AppResult.Failed>(
            f.complete(
                f.base.runtime,
                captured.copy(
                    traversal = captured.traversal.copy(deviceId = f.base.runtime.deviceId)
                ),
            )
        )
        assertEquals(0, f.transition.writes)
    }

    @Test
    fun companyCompletionRejectsPublishedProviderProgress() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        f.provider.progress =
            assertNotNull(f.provider.progress)
                .copy(
                    provider =
                        CollaborationHistoryProviderIdentity(
                            f.base.position.companyId,
                            f.base.runtime.deviceId,
                        )
                )
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.transition.writes)
    }

    @Test
    fun missingFlowAndMalformedPendingResultAreNotEmptyContent() = runTest {
        for (empty in listOf(true, false)) {
            val f = SpaceOriginalQueueCompletionFixture()
            if (empty) f.pending.emptyFlow = true
            else
                f.pending.malformedPage =
                    CollaborationHistoryReferences(
                        f.provider.progress,
                        listOf(f.pending.head()),
                        null,
                    )
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.transition.writes)
        }
    }

    @Test
    fun independentProducerBeforeCasCannotBeOverwrittenAndDoesNotLoop() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        f.provider.hooks["write"] = { f.transition.independentProducer() }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(f.pending.room.captured, f.retainedTask())
        assertEquals("later", assertNotNull(f.base.snapshot).demand.sources.single().position)
        assertEquals(1, f.transition.writes)
    }

    @Test
    fun lostCommitReplyCanBeConfirmedAfterIndependentProducerProgress() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        f.transition.lostReply = true
        f.provider.hooks["written"] = { f.transition.independentProducer() }
        assertEquals(AppResult.Success(true), f.run())
        assertNull(f.retainedTask())
        assertEquals("later", assertNotNull(f.base.snapshot).demand.sources.single().position)
        assertEquals(1, f.transition.writes)
    }

    @Test
    fun failedUncommittedAndFabricatedRepliesNeverCompleteTheTask() = runTest {
        for (fabricated in listOf(false, true)) {
            val f = SpaceOriginalQueueCompletionFixture()
            f.transition.commit = false
            f.transition.lostReply = !fabricated
            f.transition.fabricatedReply = fabricated
            assertIs<AppResult.Failed>(f.run())
            assertEquals(f.pending.room.captured, f.retainedTask())
            assertEquals(1, f.transition.writes)
        }
    }

    @Test
    fun failedReadbackAfterCommitCannotPublishACompletionReceipt() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        f.provider.hooks["written"] = {
            f.provider.failures["demand"] = f.base.directory.storageFailure
        }
        assertEquals(f.base.directory.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertNull(f.retainedTask())
        assertEquals(1, f.transition.writes)
    }

    @Test
    fun newerGenerationAfterCommitRemainsQueuedDuringLostReplyConfirmation() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        val newer = f.pending.room.captured.copy(generation = f.base.id(999))
        f.transition.lostReply = true
        f.provider.hooks["written"] = {
            f.base.demand(assertNotNull(f.base.snapshot).demand.copy(rooms = listOf(newer)))
        }
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(newer, f.retainedTask())
        assertEquals(1, f.transition.writes)
    }

    @Test
    fun completionPreservesUnrelatedRescanDemand() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        f.base.demand(assertNotNull(f.base.snapshot).demand.copy(rescanGeneration = f.base.id(998)))
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(f.base.id(998), assertNotNull(f.base.snapshot).demand.rescanGeneration)
        assertEquals(1, f.transition.writes)
    }

    @Test
    fun credentialAgeDoesNotHideOwnedQueueCompletionFacts() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        f.base.native.setup.now += 365.days
        assertEquals(AppResult.Success(true), f.run())
        assertTrue(f.provider.requests.isEmpty())
    }

    @Test
    fun denialAccountSourceMetadataOrRegistrationChangesCannotPublishCompletion() = runTest {
        for (stage in listOf("pending", "source:\$head", "progress", "written")) {
            for (change in
                listOf("denial", "account", "source", "metadata", "registration", "native")) {
                val f = SpaceOriginalQueueCompletionFixture()
                f.provider.hooks[stage] = {
                    when (change) {
                        "denial" -> f.base.directory.deny()
                        "account" -> f.base.native.setup.session = null
                        "source" -> f.base.installed = null
                        "metadata" -> f.base.retained = null
                        "registration" -> f.base.registration = null
                        "native" -> {
                            val session = assertNotNull(f.base.native.setup.session)
                            f.base.native.setup.session =
                                session.copy(
                                    binding = session.binding.copy(serverId = f.base.id(999))
                                )
                        }
                    }
                }
                val failure = assertIs<AppResult.Failed>(f.run()).failure
                if (change == "denial") assertEquals(f.base.directory.denied, failure)
                if (stage == "written") assertNull(f.retainedTask())
                else assertEquals(0, f.transition.writes)
            }
        }
    }

    @Test
    fun readFailureRemainsUncertainWithoutQueueMutation() = runTest {
        for (stage in listOf("pending", "source:\$head", "progress")) {
            val f = SpaceOriginalQueueCompletionFixture()
            f.provider.failures[stage] = f.base.directory.storageFailure
            assertEquals(
                f.base.directory.storageFailure,
                assertIs<AppResult.Failed>(f.run()).failure,
            )
            assertEquals(0, f.transition.writes)
        }
    }

    @Test
    fun stalledLocalReadHasOneDeadlineAndNeverStartsProviderRecovery() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        f.provider.hooks["pending"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(0, f.transition.writes)
        assertTrue(f.provider.requests.isEmpty())
    }

    @Test
    fun cancellationAfterCommitDoesNotRestoreOldTaskOrTouchTheWake() = runTest {
        val f = SpaceOriginalQueueCompletionFixture()
        val entered = CompletableDeferred<Unit>()
        f.provider.hooks["written"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.run() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(f.retainedTask())
        assertEquals(1, f.transition.writes)
    }
}
