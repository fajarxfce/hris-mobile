package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RetireCollaborationMessagesTest {
    @Test
    fun exactCompletionRetiresOfflineAndRetryDoesNotInventAnotherSend() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare()
        val record = f.delivery.records.single()
        val receipt = f.delivery.journal.single()
        f.base.setup.now += 365.days
        assertEquals(AppResult.Success(1), f.retire())
        assertEquals(
            CollaborationMessageCompletion(record, receipt.eventIds),
            f.completions.records[record.intent.id],
        )
        assertTrue(f.delivery.records.isEmpty())
        assertTrue(f.delivery.journal.isEmpty())
        assertEquals(AppResult.Success(0), f.retire())
        assertEquals(1, f.completions.writes)
        assertEquals(1, f.removals)
        assertEquals(0, f.delivery.acquisitions)
        assertEquals(0, f.base.setup.calls)
    }

    @Test
    fun missingOriginalLegacyAndUnsentCommandsRemainPending() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare(3)
        val records = f.delivery.records
        f.original.originals.remove(records[0].intent.id)
        f.base.records.value =
            records.mapIndexed { index, record ->
                val sent = record.state as CollaborationMessageState.Sent
                when (index) {
                    1 -> record.copy(state = sent.copy(origin = null))
                    2 ->
                        record.copy(
                            state =
                                CollaborationMessageState.Staged(
                                    sent.device,
                                    sent.transactionId,
                                    sent.origin,
                                )
                        )
                    else -> record
                }
            }
        assertEquals(AppResult.Success(0), f.retire())
        assertEquals(3, f.delivery.records.size)
        assertEquals(3, f.delivery.journal.size)
        assertEquals(0, f.completions.writes)
        assertEquals(0, f.removals)
    }

    @Test
    fun laterConflictRejectsTheWholePreparedBatchBeforeAnyArchive() = runTest {
        for (kind in 0..5) {
            val f = CollaborationRetirementFixture()
            f.prepare(2)
            val id = f.delivery.records.last().intent.id
            val original = f.original.originals.getValue(id)
            f.original.originals[id] =
                when (kind) {
                    0 -> original.copy(sender = "@other:example.test")
                    1 -> original.copy(logicalId = deliveryId(999))
                    2 -> original.copy(contentHash = "b".repeat(64))
                    3 -> original.copy(text = "different")
                    4 ->
                        original.copy(
                            eventIds = listOf("$" + "unrelated"),
                            primaryEventId = "$" + "unrelated",
                        )
                    else -> original.copy(redacted = true, text = "not erased")
                }
            assertIs<AppResult.Failed>(f.retire())
            assertEquals(0, f.completions.writes)
            assertEquals(0, f.removals)
            assertEquals(2, f.delivery.records.size)
        }
    }

    @Test
    fun confirmedRedactionRetainsOriginalIdentityWithoutRequiringErasedBody() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare()
        val record = f.delivery.records.single()
        f.original.originals[record.intent.id] =
            f.original.originals.getValue(record.intent.id).copy(redacted = true, text = null)
        assertEquals(AppResult.Success(1), f.retire())
        assertEquals(record, f.completions.records.getValue(record.intent.id).record)
    }

    @Test
    fun lateAliasAfterCleanupNeedsOriginalHistoryAndCannotReplaceImmutableProof() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare()
        val receipt = f.delivery.journal.single()
        assertEquals(AppResult.Success(1), f.retire())
        val proof = f.completions.records.getValue(receipt.intent.id)
        f.delivery.journal = listOf(receipt.copy(eventIds = listOf("$" + "late")))
        assertIs<AppResult.Failed>(f.retire())
        assertEquals(1, f.delivery.journal.size)
        val original = f.original.originals.getValue(receipt.intent.id)
        f.original.originals[receipt.intent.id] =
            original.copy(eventIds = original.eventIds + ("$" + "late"))
        assertEquals(AppResult.Success(1), f.retire())
        assertEquals(proof, f.completions.records[receipt.intent.id])
        assertTrue(f.delivery.journal.isEmpty())
        assertTrue(f.delivery.records.isEmpty())
    }

    @Test
    fun lostArchiveResponseLeavesJournalForExplicitRecovery() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare()
        val original = f.delivery.records.single()
        f.completions.writeFailure = f.base.storage
        assertIs<AppResult.Failed>(f.retire())
        assertTrue(f.delivery.records.isEmpty())
        assertEquals(1, f.delivery.journal.size)
        assertEquals(0, f.removals)
        assertEquals(AppResult.Success(1), f.retire())
        assertEquals(original, f.completions.records.getValue(original.intent.id).record)
    }

    @Test
    fun cancellationAfterArchiveKeepsEvidenceAndReleasesExecutionOwner() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare()
        val committed = CompletableDeferred<Unit>()
        f.completions.afterWrite = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val job = launch { f.retire() }
        committed.await()
        job.cancelAndJoin()
        assertTrue(f.delivery.records.isEmpty())
        assertEquals(1, f.delivery.journal.size)
        assertEquals(0, f.removals)
        f.completions.afterWrite = {}
        assertEquals(AppResult.Success(1), f.retire())
    }

    @Test
    fun lostCleanupResponseDoesNotLoseCompletionOrRepeatRemoval() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare()
        f.removalFailure = f.base.storage
        assertIs<AppResult.Failed>(f.retire())
        assertEquals(1, f.completions.records.size)
        assertTrue(f.delivery.journal.isEmpty())
        assertEquals(AppResult.Success(0), f.retire())
        assertEquals(1, f.removals)
    }

    @Test
    fun aliasAddedDuringCleanupRejectsOldBatchAndIsRevalidatedOnRetry() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare()
        val receipt = f.delivery.journal.single()
        f.beforeRemove = {
            f.delivery.journal = listOf(receipt.copy(eventIds = receipt.eventIds + ("$" + "late")))
        }
        assertIs<AppResult.Failed>(f.retire())
        assertEquals(1, f.completions.records.size)
        assertEquals(1, f.delivery.journal.size)
        f.beforeRemove = {}
        assertIs<AppResult.Failed>(f.retire())
        val original = f.original.originals.getValue(receipt.intent.id)
        f.original.originals[receipt.intent.id] =
            original.copy(eventIds = original.eventIds + ("$" + "late"))
        assertEquals(AppResult.Success(1), f.retire())
    }

    @Test
    fun denialOrAccountReplacementDuringHistoryReadCannotArchive() = runTest {
        for (denied in listOf(true, false)) {
            val f = CollaborationRetirementFixture()
            f.prepare()
            f.original.beforeReadReturns = {
                if (denied) f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
                else f.base.setup.session = null
            }
            assertIs<AppResult.Failed>(f.retire())
            assertEquals(0, f.completions.writes)
            assertEquals(0, f.removals)
            assertEquals(1, f.delivery.records.size)
        }
    }

    @Test
    fun denialAfterArchiveWithholdsSuccessAndKeepsJournalForLaterAdmission() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare()
        f.completions.afterWrite = {
            f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
        }
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.retire()).failure)
        assertEquals(1, f.completions.records.size)
        assertEquals(1, f.delivery.journal.size)
        assertEquals(0, f.removals)
        f.base.gate.value = CollaborationListAdmission("admitted", null)
        assertEquals(AppResult.Success(1), f.retire())
    }

    @Test
    fun cancellationDuringHistoryReadNeverArchivesPartialBatch() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare(2)
        val reading = CompletableDeferred<Unit>()
        f.original.beforeReadReturns = {
            if (f.original.reads == 2) {
                reading.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch { f.retire() }
        reading.await()
        job.cancelAndJoin()
        assertEquals(0, f.completions.writes)
        assertEquals(2, f.delivery.records.size)
    }

    @Test
    fun boundedCleanupAdvancesThirtyTwoAtATimeAndRejectedCasRetainsFacts() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare(33)
        f.completions.rejectWrite = true
        assertIs<AppResult.Failed>(f.retire())
        assertEquals(33, f.delivery.records.size)
        f.completions.rejectWrite = false
        f.rejectRemoval = true
        assertIs<AppResult.Failed>(f.retire())
        assertEquals(32, f.completions.records.size)
        assertEquals(33, f.delivery.journal.size)
        f.rejectRemoval = false
        assertEquals(AppResult.Success(32), f.retire())
        assertEquals(1, f.delivery.journal.size)
        assertEquals(AppResult.Success(1), f.retire())
        assertEquals(33, f.completions.records.size)
    }
}
