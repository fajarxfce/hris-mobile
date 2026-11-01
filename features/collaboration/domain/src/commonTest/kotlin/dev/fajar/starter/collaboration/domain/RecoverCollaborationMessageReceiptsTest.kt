package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RecoverCollaborationMessageReceiptsTest {
    @Test
    fun exactOriginalRepairsTheJournalOfflineBeforeSeparateProjection() = runTest {
        val f = OriginalReceiptFixture()
        f.prepare()
        f.base.setup.now += 365.days
        val before = f.delivery.records.single()
        assertEquals(AppResult.Success(1), f.recover())
        assertEquals(before, f.delivery.records.single())
        val receipt = f.delivery.journal.single()
        assertEquals(before.intent, receipt.intent)
        assertEquals("original", receipt.transactionId)
        assertEquals(listOf("\$original-0"), receipt.eventIds)
        assertEquals(0, f.base.setup.calls)
        assertEquals(0, f.delivery.acquisitions)
        assertEquals(AppResult.Success(1), f.delivery.reconcile())
        assertEquals(f.origin, f.delivery.records.single().state.origin)
        assertEquals(AppResult.Success(0), f.recover())
        assertEquals(1, f.reads)
    }

    @Test
    fun absentOriginalAndLegacyCommandsRemainPendingWithoutReenqueue() = runTest {
        for (legacy in listOf(false, true)) {
            val f = OriginalReceiptFixture()
            f.prepare()
            if (legacy)
                f.base.records.value =
                    f.delivery.records.map {
                        it.copy(
                            state =
                                (it.state as CollaborationMessageState.Staged).copy(origin = null)
                        )
                    }
            else f.originals.clear()
            val before = f.delivery.records
            assertEquals(AppResult.Success(0), f.recover())
            assertEquals(before, f.delivery.records)
            assertEquals(if (legacy) 0 else 1, f.reads)
            assertTrue(f.delivery.journal.isEmpty())
        }
    }

    @Test
    fun wrongSenderLogicalIdHashBodyOrMalformedHistoryCannotConfirmAnAck() = runTest {
        for (scenario in 0..5) {
            val f = OriginalReceiptFixture()
            f.prepare()
            val id = f.delivery.records.single().intent.id
            val original = f.originals.getValue(id)
            f.originals[id] =
                when (scenario) {
                    0 -> original.copy(sender = "@other:example.test")
                    1 -> original.copy(logicalId = deliveryId(999))
                    2 -> original.copy(contentHash = "b".repeat(64))
                    3 -> original.copy(text = "changed")
                    4 -> original.copy(eventIds = emptyList())
                    else -> original.copy(eventIds = listOf("\$unrelated"))
                }
            assertIs<AppResult.Failed>(f.recover())
            assertEquals(0, f.delivery.appends)
            assertTrue(f.delivery.journal.isEmpty())
        }
    }

    @Test
    fun confirmedRedactionKeepsItsOriginalHashAndAllObservedAliases() = runTest {
        val f = OriginalReceiptFixture()
        f.prepare()
        val id = f.delivery.records.single().intent.id
        f.originals[id] =
            f.originals
                .getValue(id)
                .copy(text = null, redacted = true, eventIds = listOf("\$original-0", "\$retry"))
        assertEquals(AppResult.Success(1), f.recover())
        assertEquals(listOf("\$original-0", "\$retry"), f.delivery.journal.single().eventIds)
    }

    @Test
    fun aLaterConflictOrStorageFailureRejectsTheWholePreparedBatch() = runTest {
        for (storage in listOf(false, true)) {
            val f = OriginalReceiptFixture()
            f.prepare(2)
            val second = f.delivery.records[1].intent.id
            if (storage) f.beforeReadReturns = { f.readFailure = f.base.storage }
            else
                f.originals[second] =
                    f.originals.getValue(second).copy(contentHash = "b".repeat(64))
            assertIs<AppResult.Failed>(f.recover())
            assertEquals(0, f.delivery.appends)
        }
    }

    @Test
    fun denialOrReplacementWhileReadingCannotAppendPreparedFacts() = runTest {
        for (denied in listOf(false, true)) {
            val f = OriginalReceiptFixture()
            f.prepare()
            f.beforeReadReturns = {
                if (denied) f.base.gate.value = CollaborationListAdmission("blocked", f.base.denied)
                else f.base.setup.session = null
            }
            assertIs<AppResult.Failed>(f.recover())
            assertEquals(0, f.delivery.appends)
        }
    }

    @Test
    fun lostCommittedResponseDoesNotDuplicateReceiptOrPublishSent() = runTest {
        val f = OriginalReceiptFixture()
        f.prepare()
        f.delivery.appendFailure = f.base.storage
        assertIs<AppResult.Failed>(f.recover())
        val retained = f.delivery.journal.toList()
        assertEquals(1, retained.size)
        assertIs<CollaborationMessageState.Staged>(f.delivery.records.single().state)
        f.delivery.appendFailure = null
        assertEquals(AppResult.Success(0), f.recover())
        assertEquals(retained, f.delivery.journal)
        assertEquals(1, f.delivery.appends)
    }

    @Test
    fun lateDenialWithholdsSuccessButRetainsTheCommittedJournal() = runTest {
        val f = OriginalReceiptFixture()
        f.prepare()
        f.delivery.afterAppend = {
            f.base.gate.value = CollaborationListAdmission("blocked", f.base.denied)
        }
        assertEquals(AppResult.Failed(f.base.denied), f.recover())
        assertEquals(1, f.delivery.journal.size)
        assertIs<CollaborationMessageState.Staged>(f.delivery.records.single().state)
    }

    @Test
    fun cancellationWhileReadingNeverWritesAPartialJournal() = runTest {
        val f = OriginalReceiptFixture()
        f.prepare(2)
        val entered = CompletableDeferred<Unit>()
        f.beforeReadReturns = {
            if (f.reads == 2) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val task = launch { f.recover() }
        entered.await()
        task.cancelAndJoin()
        assertEquals(0, f.delivery.appends)
    }

    @Test
    fun batchCapacityAndUnknownPrefixesMakeBoundedProgress() = runTest {
        val f = OriginalReceiptFixture()
        f.prepare(40)
        assertEquals(AppResult.Success(32), f.recover())
        assertEquals(AppResult.Success(8), f.recover())
        assertEquals(AppResult.Success(0), f.recover())
        assertEquals(40, f.reads)
        val g = OriginalReceiptFixture()
        g.prepare(128)
        g.delivery.records.take(100).forEach { g.originals.remove(it.intent.id) }
        assertEquals(AppResult.Success(28), g.recover())
        assertEquals(128, g.reads)
    }

    @Test
    fun excessiveAliasLedgerIsRejectedWithoutPartialAppend() = runTest {
        val f = OriginalReceiptFixture()
        f.prepare(5)
        f.originals.toMap().forEach { (id, original) ->
            f.originals[id] =
                original.copy(
                    eventIds =
                        listOf(original.primaryEventId) +
                            (1..127).map { "${original.primaryEventId}-$it" }
                )
        }
        assertIs<AppResult.Failed>(f.recover())
        assertEquals(0, f.delivery.appends)
    }
}
