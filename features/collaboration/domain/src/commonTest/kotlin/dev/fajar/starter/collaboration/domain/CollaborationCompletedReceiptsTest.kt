package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationCompletedReceiptsTest {
    @Test
    fun interruptedCleanupIsRecognizedByReconciliationRecoveryAndDelivery() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare()
        f.rejectRemoval = true
        assertIs<AppResult.Failed>(f.retire())
        assertTrue(f.delivery.records.isEmpty())
        assertEquals(1, f.delivery.journal.size)
        assertEquals(AppResult.Success(0), f.delivery.reconcile())
        assertEquals(AppResult.Success(0), f.original.recover())
        val report =
            assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.delivery.deliver()).value
        assertTrue(report.confirmed.isEmpty())
        assertTrue(report.unresolved.isEmpty())
        assertEquals(0, f.delivery.windows)
        assertEquals(0, f.delivery.projections)
        assertEquals(1, f.completions.records.size)
    }

    @Test
    fun laterReceiptWithoutOriginalPrimaryAliasCannotReplaceConfirmedIdentity() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare()
        val receipt = f.delivery.journal.single()
        assertEquals(AppResult.Success(1), f.retire())
        val proof = f.completions.records.getValue(receipt.intent.id)
        f.delivery.journal = listOf(receipt.copy(eventIds = listOf("$" + "new-alias")))
        assertEquals(AppResult.Success(0), f.delivery.reconcile())
        assertEquals(AppResult.Success(0), f.original.recover())
        assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.delivery.deliver())
        assertEquals(proof, f.completions.records[receipt.intent.id])
        assertEquals(0, f.delivery.windows)
        assertEquals(0, f.delivery.projections)
    }

    @Test
    fun absentOrChangedCompletionCannotHideAnUnknownReceipt() = runTest {
        for (kind in 0..3) {
            val f = CollaborationRetirementFixture()
            f.prepare()
            val receipt = f.delivery.journal.single()
            assertEquals(AppResult.Success(1), f.retire())
            f.delivery.journal = listOf(receipt)
            val proof = f.completions.records.getValue(receipt.intent.id)
            when (kind) {
                0 -> f.completions.records.clear()
                1 ->
                    f.completions.records[receipt.intent.id] =
                        proof.copy(eventIds = listOf("$" + "different"))
                2 -> f.delivery.journal = listOf(receipt.copy(transactionId = "different"))
                else ->
                    f.completions.records[receipt.intent.id] =
                        proof.copy(
                            record =
                                proof.record.copy(
                                    state =
                                        (proof.record.state as CollaborationMessageState.Sent).copy(
                                            origin = null
                                        )
                                )
                        )
            }
            assertIs<AppResult.Failed>(f.delivery.reconcile())
            assertIs<AppResult.Failed>(f.original.recover())
            assertIs<AppResult.Failed>(f.delivery.deliver())
            assertEquals(0, f.delivery.windows)
            assertEquals(0, f.delivery.projections)
        }
    }

    @Test
    fun accountReplacementDuringCompletionLookupPreventsLatePublication() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare()
        f.rejectRemoval = true
        assertIs<AppResult.Failed>(f.retire())
        f.completions.afterRead = { f.base.setup.session = null }
        assertIs<AppResult.Failed>(f.delivery.reconcile())
        assertEquals(0, f.delivery.projections)
        assertEquals(1, f.delivery.journal.size)
    }

    @Test
    fun fullPendingQueueAndSeparateArchivedJournalStayIndependentlyBounded() = runTest {
        val f = CollaborationRetirementFixture()
        f.prepare(128)
        val completed = f.delivery.records
        completed.forEachIndexed { index, record ->
            f.completions.records[record.intent.id] =
                CollaborationMessageCompletion(record, f.delivery.journal[index].eventIds)
        }
        f.base.records.value =
            completed.mapIndexed { index, record ->
                record.copy(
                    intent = record.intent.copy(id = deliveryId(1000 + index)),
                    revision = 0,
                    state = CollaborationMessageState.Queued,
                )
            }
        assertEquals(AppResult.Success(0), f.delivery.reconcile())
        assertEquals(AppResult.Success(0), f.original.recover())
        assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.delivery.deliver())
        assertEquals(128, f.delivery.records.size)
        assertEquals(128, f.completions.records.size)
        assertEquals(0, f.delivery.windows)
        assertEquals(0, f.delivery.projections)
    }
}
