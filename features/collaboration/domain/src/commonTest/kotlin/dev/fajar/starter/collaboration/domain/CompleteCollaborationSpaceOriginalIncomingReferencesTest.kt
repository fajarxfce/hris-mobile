package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CompleteCollaborationSpaceOriginalIncomingReferencesTest {
    @Test
    fun retainedBatchResolvesBeforeAdvancingOnlyItsOriginalCompanySeek() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        assertEquals(CollaborationSpaceIncomingReferenceCompletion(2, 0, false, false), success(f))
        assertEquals(f.references.events, f.committed)
        assertEquals(
            listOf("resolve", "resolved", "advance"),
            f.provider.calls.filter { it in setOf("resolve", "resolved", "advance") },
        )
        assertEquals(f.references, f.advances.single().first)
        assertTrue(f.provider.requests.isEmpty())
        assertTrue(f.provider.saved.isEmpty())
        assertEquals(0, f.pending.transition.writes)
    }

    @Test
    fun missingKeyRetainsRetryWhileOtherRetainedSourcesFinish() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.outcome(
            0,
            CollaborationIncomingHistoryOutcome.AwaitDecryption(
                "\$pending-0",
                CollaborationEventDecryptionFailure.UnableToDecrypt,
            ),
        )
        assertEquals(CollaborationSpaceIncomingReferenceCompletion(1, 1, false, true), success(f))
        assertEquals(f.references.events.drop(1), f.committed)
        assertTrue(f.advances.single().second)
    }

    @Test
    fun entirelyUndecryptedPageMovesTheFiniteSeekWithoutResolvingMessages() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.outcome(0, CollaborationIncomingHistoryOutcome.AwaitRedaction("\$target"))
        f.outcome(1, CollaborationIncomingHistoryOutcome.MissingOriginal("\$target"))
        assertEquals(CollaborationSpaceIncomingReferenceCompletion(0, 2, false, true), success(f))
        assertTrue(f.resolutions.isEmpty())
        assertEquals(1, f.advances.size)
    }

    @Test
    fun emptyFinalPageCarriesPriorRetryWithoutResolvingOrCallingProvider() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        val progress =
            assertNotNull(f.references.progress)
                .copy(
                    incoming = CollaborationIncomingHistoryPosition("start", null, "seek", 2, true)
                )
        f.references = CollaborationHistoryReferences(progress, emptyList(), null)
        f.provider.progress = progress
        f.acquired()
        assertEquals(CollaborationSpaceIncomingReferenceCompletion(0, 0, false, true), success(f))
        assertTrue(f.resolutions.isEmpty())
        assertTrue(f.provider.requests.isEmpty())
        assertTrue(f.advances.single().second)
    }

    @Test
    fun falseResolutionDoesNotAdvanceOrReportSuccess() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.commitResolve = false
        f.resolveResult = AppResult.Success(false)
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.committed.isEmpty())
        assertTrue(f.advances.isEmpty())
    }

    @Test
    fun lostResolveReplyKeepsCommittedFactsAndDoesNotAdvance() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.resolveResult = AppResult.Failed(f.base.directory.storageFailure)
        assertEquals(f.base.directory.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(f.references.events, f.committed)
        assertTrue(f.advances.isEmpty())
    }

    @Test
    fun falseAndLostSeekRepliesCannotBeAcknowledgedOrRetriedInline() = runTest {
        for (lost in listOf(false, true)) {
            val f = SpaceOriginalReferenceCompletionFixture()
            f.advanceResult =
                if (lost) AppResult.Failed(f.base.directory.storageFailure)
                else AppResult.Success(false)
            assertIs<AppResult.Failed>(f.run())
            assertEquals(f.references.events, f.committed)
            assertEquals(1, f.resolutions.size)
            assertEquals(1, f.advances.size)
            assertEquals(0, f.pending.transition.writes)
        }
    }

    @Test
    fun confirmedDenialWinsBeforeWritesAndAfterEitherCommit() = runTest {
        for (stage in listOf("progress", "resolved", "advance")) {
            val f = SpaceOriginalReferenceCompletionFixture()
            f.provider.hooks[stage] = { f.base.directory.deny() }
            assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.run()).failure)
            if (stage == "progress") assertTrue(f.resolutions.isEmpty())
            if (stage == "resolved") assertTrue(f.advances.isEmpty())
            assertEquals(0, f.pending.transition.writes)
        }
    }

    @Test
    fun ordinaryCredentialAgeDoesNotHideOwnedCompletion() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.base.native.setup.now += 365.days
        assertEquals(2, success(f).resolved)
        assertTrue(f.provider.requests.isEmpty())
    }

    @Test
    fun changedSourceMetadataRegistrationAccountAndNativeStopAfterResolution() = runTest {
        for (change in listOf("source", "metadata", "registration", "account", "native")) {
            val f = SpaceOriginalReferenceCompletionFixture()
            f.provider.hooks["resolved"] = {
                when (change) {
                    "source" -> f.base.installed = f.base.source.copy(storeId = "b".repeat(64))
                    "metadata" -> f.base.retained = null
                    "registration" ->
                        f.base.registration =
                            assertNotNull(f.base.registration).copy(revision = 999)
                    "account" -> f.base.native.setup.session = null
                    "native" -> {
                        val session = assertNotNull(f.base.native.setup.session)
                        f.base.native.setup.session =
                            session.copy(binding = session.binding.copy(serverId = f.base.id(999)))
                    }
                }
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(f.references.events, f.committed)
            assertTrue(f.advances.isEmpty())
        }
    }

    @Test
    fun removedTaskAfterResolutionReturnsNoCompletion() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.provider.hooks["resolved"] = {
            f.base.demand(assertNotNull(f.base.snapshot).demand.copy(rooms = emptyList()))
        }
        assertEquals(AppResult.Success(null), f.run())
        assertEquals(f.references.events, f.committed)
        assertTrue(f.advances.isEmpty())
    }

    @Test
    fun supersededTaskBeforeReadsReturnsNoWork() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        val captured = f.pending.room
        f.pending.capture(captured.captured.copy(generation = f.base.id(999)))
        assertEquals(
            AppResult.Success(null),
            f.complete(f.runtime, captured, f.references, f.batch),
        )
        assertFalse("progress" in f.provider.calls)
        assertTrue(f.resolutions.isEmpty())
    }

    @Test
    fun changedPendingTaskWithSameGenerationIsNotTheCapturedWork() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        val captured = f.pending.room
        f.pending.capture(captured.captured.copy(phase = CollaborationSpaceIncomingPhase.Provider))
        assertIs<AppResult.Failed>(f.complete(f.runtime, captured, f.references, f.batch))
        assertTrue(f.resolutions.isEmpty())
    }

    @Test
    fun independentProducerCanAdvanceSourcePositionWithoutInvalidatingThisTask() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.provider.hooks["resolved"] = { f.pending.transition.independentProducer() }
        assertEquals(2, success(f).resolved)
        assertEquals(1, f.advances.size)
    }

    @Test
    fun changedGenerationWaveProviderOrRegressingCountsStopBeforeResolution() = runTest {
        val seed = SpaceOriginalReferenceCompletionFixture()
        val progress = assertNotNull(seed.references.progress)
        for (changed in
            listOf(
                null,
                progress.copy(generation = seed.base.id(999)),
                progress.copy(revision = -1),
                progress.copy(references = 1),
                progress.copy(incoming = assertNotNull(progress.incoming).copy(head = "another")),
                progress.copy(
                    provider =
                        CollaborationHistoryProviderIdentity(
                            seed.base.position.companyId,
                            seed.runtime.deviceId,
                        )
                ),
            )) {
            val f = SpaceOriginalReferenceCompletionFixture()
            f.provider.progress = changed
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.resolutions.isEmpty())
            assertTrue(f.advances.isEmpty())
        }
    }

    @Test
    fun progressReadFailureStaysAReadFailureWithoutMutation() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.provider.failures["progress"] = f.base.directory.storageFailure
        assertEquals(f.base.directory.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertTrue(f.resolutions.isEmpty())
        assertTrue(f.advances.isEmpty())
    }

    @Test
    fun callerCannotMutateCapturedSourcesOrNestedOutcomeIdsAfterAdmission() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        val expected = f.references.events.toList()
        val source = expected.toMutableList()
        val ids = mutableListOf("\$retained-target")
        val items = assertNotNull(f.batch).items.toMutableList()
        items[0] =
            items[0].copy(outcome = CollaborationIncomingHistoryOutcome.Retained(ids, emptyList()))
        f.references = f.references.copy(events = source)
        f.batch = assertNotNull(f.batch).copy(items = items)
        f.provider.hooks["metadata"] = {
            source.clear()
            ids.clear()
            items.clear()
        }
        assertEquals(2, success(f).resolved)
        assertEquals(expected, f.committed)
        assertEquals(expected, f.advances.single().first.events)
    }

    @Test
    fun cancellationDuringResolutionNeverAdvancesOrCompletesTheQueue() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        val entered = CompletableDeferred<Unit>()
        f.provider.hooks["resolve"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val work = async { f.run() }
        entered.await()
        work.cancelAndJoin()
        assertTrue(work.isCancelled)
        assertTrue(f.committed.isEmpty())
        assertTrue(f.advances.isEmpty())
        assertEquals(0, f.pending.transition.writes)
    }

    @Test
    fun blockedLocalSeekHasOneDeadlineAndNoImmediateReplay() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        f.provider.hooks["advance"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(1, f.advances.size)
        assertEquals(f.references.events, f.committed)
    }

    @Test
    fun invalidPhaseOversizedBatchAndGroupReceiptFailBeforeStorage() = runTest {
        val f = SpaceOriginalReferenceCompletionFixture()
        val room = f.pending.room
        assertIs<AppResult.Failed>(
            f.complete(
                f.runtime,
                room.copy(
                    captured = room.captured.copy(phase = CollaborationSpaceIncomingPhase.Provider)
                ),
                f.references,
                f.batch,
            )
        )
        assertIs<AppResult.Failed>(
            f.complete(
                f.runtime,
                room,
                f.references.copy(events = List(17) { f.pending.reference(it) }),
                f.batch,
            )
        )
        assertIs<AppResult.Failed>(
            f.complete(
                f.runtime,
                room.copy(traversal = room.traversal.copy(deviceId = f.runtime.deviceId)),
                f.references,
                f.batch,
            )
        )
        assertTrue(f.provider.calls.isEmpty())
    }

    private suspend fun success(f: SpaceOriginalReferenceCompletionFixture) =
        assertNotNull(
            assertIs<AppResult.Success<CollaborationSpaceIncomingReferenceCompletion?>>(f.run())
                .value
        )
}
