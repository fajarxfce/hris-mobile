@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ReconcileCollaborationSpaceMessagesTest {
    @Test
    fun exactOfflineProjectionReadsBackTheOriginalWithoutTransportOrCompany() = runTest {
        val f = SpaceReceiptProjectionFixture()
        f.source.native.setup.now += 400.days
        f.source.native.setup.workspace = null
        f.source.native.setup.beforeWorkspace = { error("No selected company") }
        assertEquals(AppResult.Success(1), f.useCase()(f.access, f.conversation))
        assertEquals(f.sent, f.records[f.staged.intent.id])
        assertEquals(listOf("journal", "read", "write", "read"), f.operations)
        assertEquals(listOf(f.staged), f.writes)
        assertEquals(AppResult.Success(listOf(f.original)), f.journal)
        assertTrue(f.archives.isEmpty())
        assertTrue(f.source.schedules.isEmpty())
    }

    @Test
    fun committedWriteWithLostOrFalseResponseIsConfirmedByExactReadback() = runTest {
        for (result in
            listOf(
                AppResult.Success(false),
                AppResult.Failed(Failure(FailureKind.Storage, "test.lost")),
            )) {
            val f = SpaceReceiptProjectionFixture()
            f.writeResult = result
            assertEquals(AppResult.Success(1), f.useCase()(f.access, f.conversation))
            assertEquals(f.sent, f.records[f.staged.intent.id])
            assertEquals(1, f.writes.size)
        }
    }

    @Test
    fun interruptedProjectionReopensWithSameFactsAndNeverWritesAgain() = runTest {
        val f = SpaceReceiptProjectionFixture()
        assertEquals(AppResult.Success(1), f.useCase()(f.access, f.conversation))
        val rebound = f.access.copy(sessionId = "replacement")
        f.source.identity.value =
            AppResult.Success(f.source.native.session.copy(id = rebound.sessionId))
        assertEquals(AppResult.Success(0), f.useCase()(rebound, f.conversation))
        assertEquals(1, f.writes.size)
    }

    @Test
    fun uncommittedFailedResponseRetainsItsClassificationAndJournal() = runTest {
        val f = SpaceReceiptProjectionFixture()
        f.retainWrite = false
        f.writeResult = AppResult.Failed(f.source.base.storageFailure)
        assertEquals(
            assertIs<AppResult.Failed>(f.writeResult),
            f.useCase()(f.access, f.conversation),
        )
        assertEquals(f.staged, f.records[f.staged.intent.id])
        assertEquals(AppResult.Success(listOf(f.original)), f.journal)
        assertEquals(1, f.writes.size)
    }

    @Test
    fun missingOrChangedReadbackCannotConfirmSuccessfulOrCompetingWrite() = runTest {
        val changes: List<(CollaborationSpaceMessageRecord) -> CollaborationSpaceMessageRecord?> =
            listOf(
                { null },
                { it.copy(revision = it.revision + 1) },
                { it.copy(intent = it.intent.copy(text = "changed")) },
                {
                    it.copy(
                        state =
                            (it.state as CollaborationMessageState.Sent).copy(
                                transactionId = "different"
                            )
                    )
                },
                {
                    it.copy(
                        state =
                            (it.state as CollaborationMessageState.Sent).copy(
                                eventId = "\$different"
                            )
                    )
                },
            )
        for (change in changes) {
            val f = SpaceReceiptProjectionFixture()
            f.savedRecord = change
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.useCase()(f.access, f.conversation)).failure.kind,
            )
            assertEquals(1, f.writes.size)
        }
    }

    @Test
    fun falseCompareAndSwapWithoutExactTransitionIsNotRetried() = runTest {
        val f = SpaceReceiptProjectionFixture()
        f.retainWrite = false
        f.writeResult = AppResult.Success(false)
        assertIs<AppResult.Failed>(f.useCase()(f.access, f.conversation))
        assertEquals(1, f.writes.size)
        assertEquals(2, f.operations.count { it == "read" })
    }

    @Test
    fun emptyJournalReadsNoCommandsAndDoesNotInferAcknowledgement() = runTest {
        val f = SpaceReceiptProjectionFixture()
        f.journal = AppResult.Success(emptyList())
        assertEquals(AppResult.Success(0), f.useCase()(f.access, f.conversation))
        assertEquals(listOf("journal"), f.operations)
        assertEquals(f.staged, f.records[f.staged.intent.id])
    }

    @Test
    fun malformedOrOversizedJournalFailsBeforeCommandReads() = runTest {
        val f = SpaceReceiptProjectionFixture()
        for (journal in
            listOf(
                listOf(f.original, f.original),
                (1..129).map(f::receipt),
                listOf(f.original.copy(eventIds = listOf("invalid"))),
            )) {
            f.journal = AppResult.Success(journal)
            assertIs<AppResult.Failed>(f.useCase()(f.access, f.conversation))
        }
        assertEquals(listOf("journal", "journal", "journal"), f.operations)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun missingOrInvalidLastOriginalPreventsAllWrites() = runTest {
        for (record in
            listOf<CollaborationSpaceMessageRecord?>(
                null,
                SpaceReceiptProjectionFixture().source.record(),
            )) {
            val f = SpaceReceiptProjectionFixture()
            val second = f.receipt(2)
            f.journal = AppResult.Success(listOf(f.original, second))
            if (record != null) f.records[second.intent.id] = record
            assertIs<AppResult.Failed>(f.useCase()(f.access, f.conversation))
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test
    fun archivedLateAliasNeedsExactCompletionAndDoesNotRewriteSent() = runTest {
        val f = SpaceReceiptProjectionFixture()
        f.records[f.staged.intent.id] = f.sent
        f.journal = AppResult.Success(listOf(f.original.copy(eventIds = listOf("\$late"))))
        assertIs<AppResult.Failed>(f.useCase()(f.access, f.conversation))
        f.archives[f.staged.intent.id] = f.completion
        assertEquals(AppResult.Success(0), f.useCase()(f.access, f.conversation))
        assertTrue(f.writes.isEmpty())
        assertEquals(f.completion, f.archives[f.staged.intent.id])
    }

    @Test
    fun storageFailuresAreNotReceiptOrArchiveAbsence() = runTest {
        for (phase in listOf("journal", "read", "archive")) {
            val f = SpaceReceiptProjectionFixture()
            when (phase) {
                "journal" -> f.journal = AppResult.Failed(f.source.base.storageFailure)
                "read" -> f.readFailure = f.source.base.storageFailure
                "archive" -> {
                    f.records[f.staged.intent.id] = f.sent
                    f.archiveFailure = f.source.base.storageFailure
                }
            }
            assertEquals(
                AppResult.Failed(f.source.base.storageFailure),
                f.useCase()(f.access, f.conversation),
            )
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test
    fun confirmedDenialBeforeOrDuringIoWinsOverStorageFailure() = runTest {
        for (phase in listOf("before", "journal", "read", "archive", "write")) {
            val f = SpaceReceiptProjectionFixture()
            if (phase == "before") f.source.base.deny()
            if (phase == "archive") f.records[f.staged.intent.id] = f.sent
            f.afterIo = { if (it == phase) f.source.base.deny() }
            if (phase == "journal") f.journal = AppResult.Failed(f.source.base.storageFailure)
            if (phase == "read") f.readFailure = f.source.base.storageFailure
            if (phase == "archive") f.archiveFailure = f.source.base.storageFailure
            if (phase == "write") f.writeResult = AppResult.Failed(f.source.base.storageFailure)
            assertEquals(
                AppResult.Failed(f.source.base.denied),
                f.useCase()(f.access, f.conversation),
            )
            if (phase != "write") assertTrue(f.writes.isEmpty())
        }
    }

    @Test
    fun accountReplacementAtEveryReadOrWritePreventsOldSuccess() = runTest {
        for (phase in listOf("journal", "read", "archive", "write")) {
            val f = SpaceReceiptProjectionFixture()
            if (phase == "archive") f.records[f.staged.intent.id] = f.sent
            f.afterIo = {
                if (it == phase)
                    f.source.identity.value =
                        AppResult.Success(f.source.native.session.copy(id = "other"))
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.useCase()(f.access, f.conversation)).failure.kind,
            )
            if (phase != "write") assertTrue(f.writes.isEmpty())
        }
    }

    @Test
    fun replacementDuringAdmissionCannotPublishLocalFacts() = runTest {
        val f = SpaceReceiptProjectionFixture()
        f.beforeAdmission = { f.source.identity.value = AppResult.Success(null) }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.useCase()(f.access, f.conversation)).failure.kind,
        )
        assertTrue(f.operations.isEmpty())
    }

    @Test
    fun invalidScopeOrAdmissionDoesNotTouchJournal() = runTest {
        val f = SpaceReceiptProjectionFixture()
        assertIs<AppResult.Failed>(f.useCase()(f.access, "invalid"))
        assertIs<AppResult.Failed>(f.useCase()(f.access.copy(sessionId = ""), f.conversation))
        f.source.base.gate.value =
            AppResult.Success(CollaborationSpaceDirectoryAdmission(f.source.base.revision, null))
        assertIs<AppResult.Failed>(f.useCase()(f.access, f.conversation))
        assertTrue(f.operations.isEmpty())
    }

    @Test
    fun receiptAndArchiveListsAreDetachedBeforeAdmissionSuspends() = runTest {
        for (phase in listOf("journal", "archive")) {
            val f = SpaceReceiptProjectionFixture()
            val events = f.original.eventIds.toMutableList()
            val journal = mutableListOf(f.original.copy(eventIds = events))
            if (phase == "journal") f.journal = AppResult.Success(journal)
            else {
                f.records[f.staged.intent.id] = f.sent
                f.archives[f.staged.intent.id] = f.completion.copy(eventIds = events)
            }
            f.afterIo = {
                if (it == phase)
                    f.beforeAdmission = {
                        events.clear()
                        journal.clear()
                    }
            }
            assertEquals(
                AppResult.Success(if (phase == "journal") 1 else 0),
                f.useCase()(f.access, f.conversation),
            )
        }
    }

    @Test
    fun cancellationAfterCommitLeavesOriginalReceiptForNextInvocation() = runTest {
        val f = SpaceReceiptProjectionFixture()
        f.afterIo = { if (it == "write") throw CancellationException("after commit") }
        assertFailsWith<CancellationException> { f.useCase()(f.access, f.conversation) }
        assertEquals(f.sent, f.records[f.staged.intent.id])
        assertEquals(AppResult.Success(listOf(f.original)), f.journal)
        f.afterIo = {}
        assertEquals(AppResult.Success(0), f.useCase()(f.access, f.conversation))
        assertEquals(1, f.writes.size)
    }

    @Test
    fun oneDeadlineBoundsSuspendedIoWithoutRetryOrMutation() = runTest {
        val f = SpaceReceiptProjectionFixture()
        f.afterIo = { if (it == "journal") awaitCancellation() }
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(f.useCase()(f.access, f.conversation)).failure.kind,
        )
        assertEquals(15_000L, testScheduler.currentTime)
        assertEquals(listOf("journal"), f.operations)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun concurrentProjectionSharesOneExecutionAndObservesTheFirstCompletion() = runTest {
        val f = SpaceReceiptProjectionFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.afterIo = {
            if (it == "write") {
                entered.complete(Unit)
                release.await()
            }
        }
        val useCase = f.useCase()
        val first = async { useCase(f.access, f.conversation) }
        entered.await()
        val second = async { useCase(f.access, f.conversation) }
        runCurrent()
        assertEquals(1, f.operations.count { it == "journal" })
        release.complete(Unit)
        assertEquals(AppResult.Success(1), first.await())
        assertEquals(AppResult.Success(0), second.await())
        assertEquals(1, f.writes.size)
    }

    @Test
    fun failedReadbackDoesNotUndoCommittedStateOrLoseTheOriginalReceipt() = runTest {
        val f = SpaceReceiptProjectionFixture()
        f.afterIo = { if (it == "write") f.readFailure = f.source.base.storageFailure }
        assertEquals(
            AppResult.Failed(f.source.base.storageFailure),
            f.useCase()(f.access, f.conversation),
        )
        assertEquals(f.sent, f.records[f.staged.intent.id])
        assertEquals(AppResult.Success(listOf(f.original)), f.journal)
        f.afterIo = {}
        f.readFailure = null
        assertEquals(AppResult.Success(0), f.useCase()(f.access, f.conversation))
        assertEquals(1, f.writes.size)
    }

    @Test
    fun denialArrivingDuringReadbackPreventsSuccessAfterCommit() = runTest {
        val f = SpaceReceiptProjectionFixture()
        f.afterIo = { if (it == "read" && f.writes.isNotEmpty()) f.source.base.deny() }
        assertEquals(AppResult.Failed(f.source.base.denied), f.useCase()(f.access, f.conversation))
        assertEquals(f.sent, f.records[f.staged.intent.id])
        assertEquals(1, f.writes.size)
    }

    @Test
    fun cancelledWaiterDoesNotCancelTheOwnerOrStartAnotherProjection() = runTest {
        val f = SpaceReceiptProjectionFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.afterIo = {
            if (it == "write") {
                entered.complete(Unit)
                release.await()
            }
        }
        val useCase = f.useCase()
        val owner = async { useCase(f.access, f.conversation) }
        entered.await()
        val waiter = launch { useCase(f.access, f.conversation) }
        runCurrent()
        waiter.cancelAndJoin()
        release.complete(Unit)
        assertEquals(AppResult.Success(1), owner.await())
        assertEquals(1, f.operations.count { it == "journal" })
        assertEquals(1, f.writes.size)
    }

    @Test
    fun partialProjectionKeepsAllReceiptsAndFinishesRemainingOriginalsOnReopen() = runTest {
        val f = SpaceReceiptProjectionFixture()
        val second = f.receipt(2)
        f.journal = AppResult.Success(listOf(f.original, second))
        f.records[second.intent.id] = f.staged(second)
        f.afterIo = {
            if (it == "write" && f.writes.size == 1) throw CancellationException("first retained")
        }
        assertFailsWith<CancellationException> { f.useCase()(f.access, f.conversation) }
        f.afterIo = {}
        assertEquals(AppResult.Success(1), f.useCase()(f.access, f.conversation))
        assertEquals(2, f.writes.size)
        assertEquals(
            listOf(f.original, second),
            assertIs<AppResult.Success<List<CollaborationSpaceMessageReceipt>>>(f.journal).value,
        )
        assertTrue(f.records.values.all { it.state is CollaborationMessageState.Sent })
    }
}
