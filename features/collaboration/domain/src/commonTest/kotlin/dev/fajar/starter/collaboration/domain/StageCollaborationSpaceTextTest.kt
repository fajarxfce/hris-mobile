@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class StageCollaborationSpaceTextTest {
    @Test
    fun olderBootstrapStagingStillExcludesTheSamePhysicalQueue() = runTest {
        for (company in listOf(false, true)) {
            val f = SpaceMessageStagingFixture()
            val id = f.base.queue.id(999)
            val state =
                CollaborationMessageState.Staging(
                    f.base.queue.device.copy(
                        bootstrapOperationId = f.base.queue.id(998),
                        nativeSessionId = f.base.queue.id(997),
                    ),
                    emptySet(),
                    f.base.queue.origin,
                )
            if (company) {
                val previous = f.base.queue.record
                f.base.records[id] =
                    previous.copy(intent = previous.intent.copy(id = id), state = state)
            } else
                f.records[id] =
                    f.initial.copy(intent = f.intent.copy(id = id), revision = 1, state = state)
            assertIs<AppResult.Failed>(f.stage())
            assertEquals(0, f.base.count("stage-enqueue"))
            assertEquals(f.initial, f.current())
        }
    }

    @Test
    fun queuedIntentIsRetainedBeforeOnePausedEnqueueAndKeepsOriginalIdentity() = runTest {
        val f = SpaceMessageStagingFixture()
        val before = f.base.snapshot.original.messages.toList()
        val result = assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage()).value
        assertEquals(f.intent, result.intent)
        assertEquals(2L, result.revision)
        val state = assertIs<CollaborationMessageState.Staged>(result.state)
        assertEquals(f.transactionId, state.transactionId)
        assertEquals(f.base.queue.device, state.device)
        assertEquals(before, f.base.snapshot.original.messages.dropLast(1))
        assertEquals(1, f.base.count("stage-enqueue"))
        assertEquals(1, f.base.count("stage-close"))
        assertFalse(f.activeWriter)
        f.noDelivery()
    }

    @Test
    fun lostEnqueueResponseRecoversTheSameOriginalWithoutRepeatingEnqueue() = runTest {
        val f = SpaceMessageStagingFixture()
        f.enqueueResponse = AppResult.Failed(f.base.directory.storageFailure)
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
        assertEquals(1, f.base.count("stage-enqueue"))
        assertEquals(1, f.base.count("stage-acquire"))
        f.noDelivery()
    }

    @Test
    fun enqueueFailureWithoutCommitRetainsAttemptUntilAnExplicitQuiescentRetry() = runTest {
        val f = SpaceMessageStagingFixture()
        f.commitEnqueue = false
        f.enqueueResponse = AppResult.Failed(f.base.directory.storageFailure)
        assertIs<AppResult.Failed>(f.stage())
        val retained = f.current()
        assertIs<CollaborationMessageState.Staging>(retained.state)
        assertEquals(1L, retained.revision)
        f.commitEnqueue = true
        f.enqueueResponse = AppResult.Success(Unit)
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
        assertEquals(2, f.base.count("stage-enqueue"))
        assertEquals(1, f.base.snapshot.original.messages.count { it.logicalId == f.intent.id })
        f.noDelivery()
    }

    @Test
    fun cancelledEnqueueResponseLeavesRecoverableOriginalAndClosesTheLease() = runTest {
        val f = SpaceMessageStagingFixture()
        f.base.hooks["stage-enqueued"] = { throw CancellationException("Lost waiter") }
        assertFailsWith<CancellationException> { f.stage() }
        assertIs<CollaborationMessageState.Staging>(f.current().state)
        assertFalse(f.activeWriter)
        f.base.hooks.remove("stage-enqueued")
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
        assertEquals(1, f.base.count("stage-enqueue"))
        assertEquals(2, f.base.count("stage-close"))
        f.noDelivery()
    }

    @Test
    fun falseAndFailedStagingResponsesMustBeConfirmedByExactReadback() = runTest {
        for (response in
            listOf(
                AppResult.Success(false),
                AppResult.Failed(Failure(FailureKind.Storage, "Lost response")),
            )) {
            val f = SpaceMessageStagingFixture()
            f.stagingResponse = response
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
            assertEquals(1, f.base.count("stage-enqueue"))
            f.noDelivery()
        }
    }

    @Test
    fun uncommittedStagingNeverPermitsEnqueue() = runTest {
        val f = SpaceMessageStagingFixture()
        f.commitStaging = false
        f.stagingResponse = AppResult.Success(false)
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(f.initial, f.current())
        assertEquals(0, f.base.count("stage-enqueue"))
        assertFalse(f.activeWriter)
    }

    @Test
    fun lostStagedWriteResponseIsConfirmedWithoutReplacingTheTransaction() = runTest {
        for (response in
            listOf(
                AppResult.Success(false),
                AppResult.Failed(Failure(FailureKind.Storage, "Lost response")),
            )) {
            val f = SpaceMessageStagingFixture()
            f.stagedResponse = response
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
            assertEquals(
                f.transactionId,
                assertIs<CollaborationMessageState.Staged>(f.current().state).transactionId,
            )
            assertEquals(1, f.base.count("stage-enqueue"))
        }
    }

    @Test
    fun uncommittedStagedWriteRecoversFromTheOriginalOnNextAttempt() = runTest {
        val f = SpaceMessageStagingFixture()
        f.commitStaged = false
        f.stagedResponse = AppResult.Success(false)
        assertIs<AppResult.Failed>(f.stage())
        assertIs<CollaborationMessageState.Staging>(f.current().state)
        f.commitStaged = true
        f.stagedResponse = AppResult.Success(true)
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
        assertEquals(1, f.base.count("stage-enqueue"))
    }

    @Test
    fun cancellationAfterStagingCommitDoesNotEnqueueUntilASeparateAttempt() = runTest {
        val f = SpaceMessageStagingFixture()
        f.base.hooks["store-staging-committed"] = { throw CancellationException("Caller left") }
        assertFailsWith<CancellationException> { f.stage() }
        assertIs<CollaborationMessageState.Staging>(f.current().state)
        assertEquals(0, f.base.count("stage-enqueue"))
        f.base.hooks.remove("store-staging-committed")
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
        assertEquals(1, f.base.count("stage-enqueue"))
    }

    @Test
    fun anotherUnresolvedStagingRecordBlocksTheSharedPhysicalQueue() = runTest {
        val f = SpaceMessageStagingFixture()
        val other =
            f.initial.copy(
                intent = f.intent.copy(id = f.base.queue.id(999)),
                revision = 1,
                state =
                    CollaborationMessageState.Staging(
                        f.base.queue.device,
                        emptySet(),
                        f.base.queue.origin,
                    ),
            )
        f.records[other.intent.id] = other
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(0, f.base.count("stage-enqueue"))
        assertEquals(f.initial, f.current())
    }

    @Test
    fun aCompanyCommandWithTheSameLogicalIdCannotBeReinterpretedAsSpace() = runTest {
        val f = SpaceMessageStagingFixture()
        f.base.records[f.intent.id] =
            f.base.queue.record.copy(intent = f.base.queue.record.intent.copy(id = f.intent.id))
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(0, f.base.count("stage-enqueue"))
        assertEquals(f.initial, f.current())
    }

    @Test
    fun unavailableRecipientKeepsQueuedIntentWithoutAcquiringAnSdkWriter() = runTest {
        val f = SpaceMessageStagingFixture()
        f.base.observed = f.base.observed.copy(recipientReady = false)
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.stage()).failure.kind)
        assertEquals(f.initial, f.current())
        assertEquals(0, f.base.count("stage-acquire"))
        assertEquals(0, f.base.count("stage-enqueue"))
    }

    @Test
    fun alreadyEnqueuedOriginalCanRecoverWhileRecipientIsUnavailable() = runTest {
        val f = SpaceMessageStagingFixture()
        f.commitStaged = false
        assertIs<AppResult.Failed>(f.stage())
        f.commitStaged = true
        f.base.observed = f.base.observed.copy(recipientReady = false)
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
        assertEquals(1, f.base.count("stage-enqueue"))
    }

    @Test
    fun localTerminalMappingRemainsReadableAfterCredentialExpiry() = runTest {
        val f = SpaceMessageStagingFixture()
        val staged = assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage()).value
        val session = requireNotNull(f.base.native.setup.session)
        f.base.native.setup.session =
            session.copy(
                tokens = session.tokens.copy(expiresAtEpochMillis = 1),
                binding = session.binding.copy(expiresAtEpochMillis = 1),
            )
        assertEquals(
            staged,
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage()).value,
        )
        assertEquals(1, f.base.count("transport"))
        assertEquals(1, f.base.count("stage-acquire"))
    }

    @Test
    fun directoryDenialAtEveryPreparationBoundaryPreventsEnqueue() = runTest {
        for (step in
            listOf(
                "binding",
                "transport",
                "stage-acquire",
                "stage-snapshot",
                "space-observe",
                "space-journal",
                "stage-prepare",
                "store-staging",
                "open",
            )) {
            val f = SpaceMessageStagingFixture()
            f.base.hooks[step] = { f.base.directory.deny() }
            assertEquals(
                f.base.directory.denied,
                assertIs<AppResult.Failed>(f.stage()).failure,
                step,
            )
            assertEquals(0, f.base.count("stage-enqueue"), step)
            assertFalse(f.activeWriter, step)
        }
    }

    @Test
    fun denialAfterNativeCommitPreservesAttemptAndNeverProjectsThroughClosedAdmission() = runTest {
        val f = SpaceMessageStagingFixture()
        f.base.hooks["stage-enqueued"] = { f.base.directory.deny() }
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.stage()).failure)
        assertIs<CollaborationMessageState.Staging>(f.current().state)
        assertEquals(1, f.base.snapshot.original.messages.count { it.logicalId == f.intent.id })
        assertEquals(0, f.base.count("store-staged"))
        assertFalse(f.activeWriter)
    }

    @Test
    fun replacementLoginCannotStageTheCapturedAccountsMessage() = runTest {
        val f = SpaceMessageStagingFixture()
        f.base.hooks["stage-prepare"] = {
            f.base.native.setup.session =
                requireNotNull(f.base.native.setup.session).copy(id = "replacement-login")
        }
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.stage()).failure.kind)
        assertEquals(0, f.base.count("stage-enqueue"))
        assertEquals(f.initial, f.current())
    }

    @Test
    fun timeoutReleasesWriterWithoutResettingCommittedStaging() = runTest {
        val f = SpaceMessageStagingFixture()
        f.base.hooks["store-staging-committed"] = { delay(20_000) }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.stage()).failure.kind)
        assertIs<CollaborationMessageState.Staging>(f.current().state)
        assertEquals(0, f.base.count("stage-enqueue"))
        assertFalse(f.activeWriter)
    }

    @Test
    fun competingCallerDoesNotQueueBehindAnUnfinishedStagingOperation() = runTest {
        val f = SpaceMessageStagingFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.base.hooks["stage-prepare"] = {
            entered.complete(Unit)
            release.await()
        }
        val first = async { f.stage() }
        entered.await()
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(1, f.base.count("stage-acquire"))
        first.cancelAndJoin()
        assertFalse(f.activeWriter)
        f.base.hooks.remove("stage-prepare")
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
        assertEquals(1, f.base.count("stage-enqueue"))
    }

    @Test
    fun wrongPreparedSenderCannotBeCommittedToTheInstalledQueue() = runTest {
        val f = SpaceMessageStagingFixture()
        f.preparedOrigin = f.preparedOrigin.copy(sender = "@foreign:example.test")
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(f.initial, f.current())
        assertEquals(0, f.base.count("stage-enqueue"))
    }

    @Test
    fun closeFailureDoesNotUndoOrResendAnAlreadyRetainedMapping() = runTest {
        val f = SpaceMessageStagingFixture()
        f.closeResponse = AppResult.Failed(f.base.directory.storageFailure)
        assertIs<AppResult.Failed>(f.stage())
        assertIs<CollaborationMessageState.Staged>(f.current().state)
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
        assertEquals(1, f.base.count("stage-enqueue"))
    }

    @Test
    fun acknowledgedOldOriginalAcceptsExactJournalOrArchiveProofBeforeNewStaging() = runTest {
        for (archived in listOf(false, true)) {
            val f = SpaceMessageStagingFixture()
            val sent = f.base.queue.sent(event = "\$old-accepted")
            f.base.records[sent.intent.id] = sent
            val proof = CollaborationMessageCompletion(sent, listOf("\$old-accepted"))
            if (archived) f.base.archived[sent.intent.id] = proof
            else
                f.base.journal =
                    listOf(
                        requireNotNull(
                            dev.fajar.starter.collaboration.domain.policies
                                .collaborationCompletionReceipt(proof)
                        )
                    )
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
            assertEquals(1, f.base.count("stage-enqueue"))
            assertEquals(sent, f.base.records[sent.intent.id])
            f.noDelivery()
        }
    }

    @Test
    fun unknownNativeOriginalBlocksStagingBeforeAnyCommandWrite() = runTest {
        val f = SpaceMessageStagingFixture()
        val unknown =
            f.base.queue.original.copy(
                logicalId = f.base.queue.id(999),
                transactionId = "unowned-original",
            )
        f.base.snapshot =
            f.base.snapshot.copy(
                original =
                    f.base.snapshot.original.copy(
                        messages = f.base.snapshot.original.messages + unknown
                    )
            )
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(f.initial, f.current())
        assertEquals(0, f.base.count("store-staging"))
        assertEquals(0, f.base.count("stage-enqueue"))
    }

    @Test
    fun changedCanonicalPreparationCannotRetargetAnUnresolvedOriginal() = runTest {
        val f = SpaceMessageStagingFixture()
        f.commitStaged = false
        assertIs<AppResult.Failed>(f.stage())
        val retained = f.current()
        f.commitStaged = true
        f.preparedOrigin = f.preparedOrigin.copy(contentHash = "b".repeat(64))
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(retained, f.current())
        assertEquals(1, f.base.count("stage-enqueue"))
    }

    @Test
    fun newSpaceRoomStagesWithoutAnyCompanyCommandOrReceiptRead() = runTest {
        val f = SpaceMessageStagingFixture()
        val room =
            f.base.model.room.copy(origin = CollaborationSpaceRoomOrigin.SPACE, original = null)
        f.base.retained = f.base.model.binding.copy(room = room, original = null)
        f.base.observed = f.base.observed.copy(room = room)
        f.base.snapshot =
            f.base.snapshot.copy(original = f.base.snapshot.original.copy(messages = emptyList()))
        f.base.failures["get"] = f.base.directory.storageFailure
        f.base.failures["journal"] = f.base.directory.storageFailure
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.stage())
        assertEquals(0, f.base.count("get"))
        assertEquals(0, f.base.count("journal"))
        assertEquals(1, f.base.count("stage-enqueue"))
        f.noDelivery()
    }
}
