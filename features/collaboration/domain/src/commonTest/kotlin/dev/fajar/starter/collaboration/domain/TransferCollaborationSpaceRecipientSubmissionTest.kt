package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class TransferCollaborationSpaceRecipientSubmissionTest {
    @Test
    fun retainedDestinationAndIndexPermitRemovalWithoutChangingLaterTyping() = runTest {
        val f = SpaceRecipientTransferFixture()
        val later =
            f.submission.nextDraft.copy(text = "Still typing", revision = f.directory.revision)
        f.source.draft.editor = later
        assertEquals(
            AppResult.Success(CollaborationSpaceRecipientTransfer.Transferred(f.canonical.id)),
            f.invoke(),
        )
        assertEquals(
            listOf(
                "source",
                "destination",
                "peer",
                "insert",
                "inserted",
                "destination",
                "canonical",
                "index",
                "anchor",
                "probe",
                "remove",
                "removed",
                "source",
            ),
            f.calls,
        )
        assertEquals(f.record(), f.source.destination)
        assertNull(f.source.retained[f.submission.id])
        assertEquals(later, f.source.draft.editor)
        assertEquals(0, f.source.draft.contactReads)
        assertTrue(f.directory.requests.isEmpty())
    }

    @Test
    fun missingConversationWaitsWithoutInsertionOrProvisioning() = runTest {
        val f = SpaceRecipientTransferFixture()
        f.byPeer = null
        assertEquals(
            AppResult.Success(CollaborationSpaceRecipientTransfer.WaitingForConversation(null)),
            f.invoke(),
        )
        assertEquals(listOf("source", "destination", "peer"), f.calls)
        assertEquals(f.submission, f.source.retained[f.submission.id])
        assertTrue(f.proposals.isEmpty())
    }

    @Test
    fun missingPinnedMetadataNeverRetargetsAnExistingOriginal() = runTest {
        val f = SpaceRecipientTransferFixture()
        f.source.destination = f.record()
        f.conversations.clear()
        f.byPeer = f.canonical.copy(id = f.directory.generation)
        assertEquals(
            AppResult.Success(
                CollaborationSpaceRecipientTransfer.WaitingForConversation(f.canonical.id)
            ),
            f.invoke(),
        )
        assertEquals(listOf("source", "destination", "canonical"), f.calls)
        assertTrue(f.proposals.isEmpty())
    }

    @Test
    fun absentIndexKeepsSourceAndASecondInvocationReusesTheSameDestination() = runTest {
        val f = SpaceRecipientTransferFixture()
        f.probeResult = CollaborationSpaceInboxSeedResult.DEMAND_REQUIRED
        assertEquals(
            AppResult.Success(CollaborationSpaceRecipientTransfer.WaitingForInbox(f.canonical.id)),
            f.invoke(),
        )
        assertEquals(f.submission, f.source.retained[f.submission.id])
        f.probeResult = CollaborationSpaceInboxSeedResult.RETAINED
        assertEquals(
            AppResult.Success(CollaborationSpaceRecipientTransfer.Transferred(f.canonical.id)),
            f.invoke(),
        )
        assertEquals(1, f.proposals.size)
        assertEquals(1, f.calls.count { it == "remove" })
    }

    @Test
    fun changingIndexHasThreeAttemptsAndNeverWakesItself() = runTest {
        for (mismatchedSnapshot in listOf(false, true)) {
            val f = SpaceRecipientTransferFixture()
            f.probeResult = CollaborationSpaceInboxSeedResult.CHANGED
            if (mismatchedSnapshot)
                f.anchors =
                    f.anchors.copy(
                        version = CollaborationSpaceInboxVersion(f.directory.generation, 0, 0)
                    )
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.invoke()).failure.kind)
            assertEquals(3, f.calls.count { it == "index" })
            assertEquals(if (mismatchedSnapshot) 0 else 3, f.publications.size)
            assertFalse("remove" in f.calls)
            assertEquals(1, f.proposals.size)
        }
    }

    @Test
    fun absentSourceCannotCreateAnotherMessageButExactCompletedReplaySucceeds() = runTest {
        val f = SpaceRecipientTransferFixture()
        f.source.retained.clear()
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.invoke()).failure.kind)
        assertTrue(f.proposals.isEmpty())
        f.source.destination = f.record()
        assertEquals(
            AppResult.Success(CollaborationSpaceRecipientTransfer.Transferred(f.canonical.id)),
            f.invoke(),
        )
        assertTrue(f.proposals.isEmpty())
        assertFalse("peer" in f.calls)
    }

    @Test
    fun changedSourceCannotBeTransferredEvenWithMatchingDestination() = runTest {
        val f = SpaceRecipientTransferFixture()
        f.source.retained[f.submission.id] =
            f.submission.copy(
                submittedAt = f.submission.submittedAt + kotlin.time.Duration.parse("1s")
            )
        f.source.destination = f.record()
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.invoke()).failure.kind)
        assertEquals(listOf("source"), f.calls)
    }

    @Test
    fun legacyOrDifferentRecipientProofCannotAcknowledgeTheSource() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceRecipientTransferFixture()
            val record = f.record()
            f.source.destination =
                record.copy(
                    intent =
                        record.intent.copy(
                            recipientOrigin =
                                if (legacy) null
                                else
                                    record.intent.recipientOrigin!!.copy(
                                        nextRevision = f.directory.revision
                                    )
                        )
                )
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.invoke()).failure.kind)
            assertEquals(listOf("source", "destination"), f.calls)
        }
    }

    @Test
    fun competingInsertionPinsTheWinningCanonicalTarget() = runTest {
        val f = SpaceRecipientTransferFixture()
        val winner = f.canonical.copy(id = f.directory.generation)
        f.conversations[winner.id] = winner
        f.insertedTarget = winner.id
        f.insertFailure = Failure(FailureKind.Conflict, "Competing canonical target")
        assertEquals(
            AppResult.Success(CollaborationSpaceRecipientTransfer.Transferred(winner.id)),
            f.invoke(),
        )
        assertEquals(f.canonical.id, f.proposals.single().conversationId)
        assertEquals(winner.id, f.source.destination!!.intent.conversationId)
    }

    @Test
    fun lostInsertionReplyRequiresExactReadback() = runTest {
        for (persisted in listOf(false, true)) {
            val f = SpaceRecipientTransferFixture()
            f.storeInserted = persisted
            f.insertFailure = f.directory.storageFailure
            val result = f.invoke()
            if (persisted)
                assertIs<CollaborationSpaceRecipientTransfer.Transferred>(
                    assertIs<AppResult.Success<*>>(result).value
                )
            else {
                assertEquals(AppResult.Failed(f.directory.storageFailure), result)
                assertFalse("remove" in f.calls)
            }
        }
    }

    @Test
    fun successfulInsertionReplyWithoutRetainedOriginalIsNotSuccess() = runTest {
        val f = SpaceRecipientTransferFixture()
        f.storeInserted = false
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.invoke()).failure.kind)
        assertFalse("canonical" in f.calls)
        assertEquals(f.submission, f.source.retained[f.submission.id])
    }

    @Test
    fun insertionReadbackFailureKeepsTheRecoverableSource() = runTest {
        val f = SpaceRecipientTransferFixture()
        f.hooks["inserted"] = { f.source.destinationFailure = f.directory.storageFailure }
        assertEquals(AppResult.Failed(f.directory.storageFailure), f.invoke())
        assertNotNull(f.source.destination)
        assertEquals(f.submission, f.source.retained[f.submission.id])
    }

    @Test
    fun falseOrLostRemovalReplyStillNeedsAbsenceReadback() = runTest {
        for (persisted in listOf(false, true)) for (lostReply in listOf(false, true)) {
            val f = SpaceRecipientTransferFixture()
            f.removeSource = persisted
            f.removeReply = false
            if (lostReply) f.removeFailure = f.directory.storageFailure
            val result = f.invoke()
            if (persisted)
                assertIs<CollaborationSpaceRecipientTransfer.Transferred>(
                    assertIs<AppResult.Success<*>>(result).value
                )
            else
                assertEquals(
                    if (lostReply) FailureKind.Storage else FailureKind.Conflict,
                    assertIs<AppResult.Failed>(result).failure.kind,
                )
            assertEquals("source", f.calls.last())
        }
    }

    @Test
    fun successfulRemovalReplyWithoutDeletionIsNotSuccess() = runTest {
        val f = SpaceRecipientTransferFixture()
        f.removeSource = false
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.invoke()).failure.kind)
        assertEquals(f.submission, f.source.retained[f.submission.id])
    }

    @Test
    fun removalReadbackFailureDoesNotReportCompletion() = runTest {
        val f = SpaceRecipientTransferFixture()
        f.hooks["removed"] = { f.source.readFailure = f.directory.storageFailure }
        assertEquals(AppResult.Failed(f.directory.storageFailure), f.invoke())
        assertTrue(f.source.retained.isEmpty())
        f.source.readFailure = null
        f.hooks.remove("removed")
        assertIs<CollaborationSpaceRecipientTransfer.Transferred>(
            assertIs<AppResult.Success<*>>(f.invoke()).value
        )
        assertEquals(1, f.proposals.size)
    }

    @Test
    fun wrongParticipantsOrCanonicalIdCannotRetireSource() = runTest {
        for (atPeer in listOf(false, true)) {
            val f = SpaceRecipientTransferFixture()
            val wrong = f.canonical.copy(secondAccountId = f.directory.generation)
            if (atPeer) f.byPeer = wrong else f.conversations[f.canonical.id] = wrong
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.invoke()).failure.kind,
            )
            assertFalse("remove" in f.calls)
            assertEquals(if (atPeer) 0 else 1, f.proposals.size)
        }
        val f = SpaceRecipientTransferFixture()
        f.conversations[f.canonical.id] = f.canonical.copy(id = f.directory.generation)
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(f.invoke()).failure.kind)
        assertFalse("remove" in f.calls)
    }

    @Test
    fun denialAfterEachBoundaryStopsFurtherMutations() = runTest {
        for (boundary in
            listOf(
                "source",
                "destination",
                "peer",
                "inserted",
                "canonical",
                "index",
                "anchor",
                "probe",
                "removed",
            )) {
            val f = SpaceRecipientTransferFixture()
            f.hooks[boundary] = { f.directory.deny() }
            assertEquals(AppResult.Failed(f.directory.denied), f.invoke(), boundary)
            assertEquals(boundary, f.calls.last(), boundary)
        }
    }

    @Test
    fun accountReplacementCannotAcknowledgeTheOldAccount() = runTest {
        for (boundary in listOf("source", "inserted", "probe", "removed")) {
            val f = SpaceRecipientTransferFixture()
            f.hooks[boundary] = { f.directory.identity.value = AppResult.Success(null) }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.invoke()).failure.kind,
                boundary,
            )
            assertEquals(boundary, f.calls.last(), boundary)
        }
    }

    @Test
    fun cancellationPropagatesAndLeavesCommittedEvidenceForRecovery() = runTest {
        for (boundary in listOf("peer", "inserted", "probe", "removed")) {
            val f = SpaceRecipientTransferFixture()
            val arrived = CompletableDeferred<Unit>()
            f.hooks[boundary] = {
                arrived.complete(Unit)
                awaitCancellation()
            }
            val task = async { f.invoke() }
            arrived.await()
            task.cancel()
            assertFailsWith<CancellationException> { task.await() }
            assertEquals(boundary, f.calls.last())
            assertEquals(boundary != "peer", f.source.destination != null)
            assertEquals(boundary != "removed", f.source.retained.containsKey(f.submission.id))
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun oneInvocationHasAFiniteTimeoutWithoutResubmission() = runTest {
        val f = SpaceRecipientTransferFixture()
        f.hooks["index"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.invoke()).failure.kind)
        assertEquals(5_000L, testScheduler.currentTime)
        assertEquals(1, f.proposals.size)
        assertEquals(f.submission, f.source.retained[f.submission.id])
    }

    @Test
    fun cancelledDestinationCanCompleteTransferWithoutAnotherSend() = runTest {
        val f = SpaceRecipientTransferFixture()
        f.source.destination = f.record().copy(state = CollaborationMessageState.Cancelled)
        assertEquals(
            AppResult.Success(CollaborationSpaceRecipientTransfer.Transferred(f.canonical.id)),
            f.invoke(),
        )
        assertTrue(f.proposals.isEmpty())
        assertNull(f.publications.single().activities.single().preview)
    }

    @Test
    fun impossibleWritingProbeResultAndProbeFailureDoNotRetireSource() = runTest {
        val f = SpaceRecipientTransferFixture()
        f.probeResult = CollaborationSpaceInboxSeedResult.STORED
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(f.invoke()).failure.kind)
        f.probeFailure = f.directory.storageFailure
        assertEquals(AppResult.Failed(f.directory.storageFailure), f.invoke())
        assertFalse("remove" in f.calls)
    }
}
