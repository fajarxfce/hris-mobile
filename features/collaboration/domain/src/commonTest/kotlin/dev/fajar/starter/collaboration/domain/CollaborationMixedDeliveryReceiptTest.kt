package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*

class CollaborationMixedDeliveryReceiptTest {
    private val f = MixedOriginalQueueReadFixture()
    private val companyReceipt =
        CollaborationQueueReceipt(f.base.queue.original.transactionId, "\$company")
    private val spaceReceipt = CollaborationQueueReceipt(f.original.transactionId, "\$space")
    private val progress =
        CollaborationQueueDelivery(
            listOf(companyReceipt, spaceReceipt),
            emptySet(),
            CollaborationQueueDeliveryReason.DRAINED,
        )

    private fun captured() =
        assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(
                matchCollaborationMixedOriginalQueue(
                    f.base.queue.runtime,
                    f.base.model.conversation,
                    f.base.model.binding,
                    f.base.snapshot,
                    listOf(f.base.queue.record),
                    emptyList(),
                    listOf(f.record),
                    emptyList(),
                )
            )
            .value

    @Test
    fun onePhysicalObservationKeepsDistinctJournalOwnersAndOriginalCoordinates() {
        val receipts =
            assertNotNull(
                collaborationMixedDeliveryReceipts(f.base.model.binding, captured(), progress)
            )
        assertEquals(f.base.queue.record.intent, receipts.company.single().intent)
        assertEquals(f.record.intent, receipts.space.single().intent)
        assertNotEquals(
            receipts.company.single().intent.conversationId,
            receipts.space.single().intent.conversationId,
        )
        assertEquals(f.base.queue.device, receipts.company.single().device)
        assertEquals(f.base.queue.device, receipts.space.single().device)
        assertEquals(f.original.origin, receipts.space.single().origin)
    }

    @Test
    fun unknownReceiptOrPendingTransactionRejectsBothBatches() {
        for (delivery in
            listOf(
                progress.copy(
                    receipts = progress.receipts + CollaborationQueueReceipt("unknown", "\$other")
                ),
                progress.copy(pending = setOf("unknown")),
            )) {
            assertNull(
                collaborationMixedDeliveryReceipts(f.base.model.binding, captured(), delivery)
            )
        }
    }

    @Test
    fun oneEventCannotAcknowledgeDifferentLogicalLedgers() {
        val sameEvent =
            progress.copy(
                receipts =
                    listOf(companyReceipt, spaceReceipt.copy(eventId = companyReceipt.eventId))
            )
        assertNull(collaborationMixedDeliveryReceipts(f.base.model.binding, captured(), sameEvent))
    }

    @Test
    fun duplicateAliasesForOneOriginalRemainIdempotent() {
        val deliveries =
            progress.copy(
                receipts = progress.receipts + spaceReceipt + spaceReceipt.copy(eventId = "\$alias")
            )
        val receipts =
            assertNotNull(
                collaborationMixedDeliveryReceipts(f.base.model.binding, captured(), deliveries)
            )
        assertEquals(listOf("\$space", "\$alias"), receipts.space.single().eventIds)
        assertEquals(listOf("\$company"), receipts.company.single().eventIds)
    }

    @Test
    fun incompleteOrMutatedCaptureCannotSupplyAReceiptMapping() {
        val original = captured()
        val record = assertIs<CollaborationMatchedQueueMessage.Space>(original.messages.last())
        for (changed in
            listOf(
                original.copy(messages = original.messages.dropLast(1)),
                original.copy(
                    messages =
                        listOf(
                            original.messages.first(),
                            record.copy(
                                record =
                                    record.record.copy(
                                        intent =
                                            record.record.intent.copy(
                                                conversationId =
                                                    f.base.queue.record.intent.conversationId
                                            )
                                    )
                            ),
                        )
                ),
                original.copy(device = original.device.copy(deviceId = f.base.queue.id(990))),
            )) assertNull(
            collaborationMixedDeliveryReceipts(f.base.model.binding, changed, progress)
        )
    }

    @Test
    fun noObservedReceiptNeverBecomesAnAcknowledgement() {
        val empty =
            progress.copy(
                receipts = emptyList(),
                pending = setOf(f.original.transactionId, f.base.queue.original.transactionId),
                reason = CollaborationQueueDeliveryReason.STOPPED,
            )
        val receipts =
            assertNotNull(
                collaborationMixedDeliveryReceipts(f.base.model.binding, captured(), empty)
            )
        assertTrue(receipts.company.isEmpty())
        assertTrue(receipts.space.isEmpty())
    }

    @Test
    fun settlementRequiresExactSpaceCompletionAndPhysicalOriginal() {
        val proof = CollaborationSpaceMessageCompletion(f.sent(), listOf("\$space-accepted"))
        assertEquals(
            "\$space-accepted",
            collaborationSpaceQueueCompletionEvent(
                f.access,
                f.record.intent.conversationId,
                f.base.queue.device,
                f.original,
                proof,
            ),
        )
        for (original in
            listOf(
                f.original.copy(transactionId = "other"),
                f.original.copy(logicalId = f.base.queue.id(991)),
                f.original.copy(origin = f.original.origin.copy(contentHash = "b".repeat(64))),
            )) {
            assertNull(
                collaborationSpaceQueueCompletionEvent(
                    f.access,
                    f.record.intent.conversationId,
                    f.base.queue.device,
                    original,
                    proof,
                )
            )
        }
        assertNull(
            collaborationSpaceQueueCompletionEvent(
                f.access,
                f.base.queue.record.intent.conversationId,
                f.base.queue.device,
                f.original,
                proof,
            )
        )
    }

    @Test
    fun newSpaceMappingDoesNotBorrowTheLegacyBootstrapAliasException() {
        val otherDevice = f.base.queue.device.copy(bootstrapOperationId = f.base.queue.id(992))
        assertNull(
            collaborationSpaceDeliveryReceipts(
                f.access,
                f.record.intent.conversationId,
                progress.copy(receipts = listOf(spaceReceipt)),
                mapOf(f.original.transactionId to f.record),
                otherDevice,
            )
        )
    }

    @Test
    fun unresolvedWorkInEitherLedgerSurvivesAnEmptyNativeQueueReport() {
        val report =
            collaborationMixedDeliveryReport(
                listOf(f.base.queue.record),
                listOf(f.sent()),
                CollaborationQueueDeliveryReason.DRAINED,
            )
        assertEquals(setOf(f.record.intent.id), report.confirmed)
        assertEquals(setOf(f.base.queue.record.intent.id), report.unresolved)
        assertEquals(CollaborationQueueDeliveryReason.UNRESOLVED, report.nativeReason)
    }

    @Test
    fun completeLedgersCannotShareLogicalTransactionOrEventOwnershipAcrossBootstrapAliases() {
        val receipts =
            assertNotNull(
                collaborationMixedDeliveryReceipts(f.base.model.binding, captured(), progress)
            )
        assertTrue(validCollaborationMixedReceiptOwnership(receipts.company, receipts.space))
        val old = receipts.company.single()
        val next = receipts.space.single()
        val rebound = next.device.copy(bootstrapOperationId = f.base.queue.id(995))
        for (changed in
            listOf(
                next.copy(intent = next.intent.copy(id = old.intent.id)),
                next.copy(device = rebound, transactionId = old.transactionId),
                next.copy(eventIds = old.eventIds),
            )) assertFalse(
            validCollaborationMixedReceiptOwnership(receipts.company, listOf(changed))
        )
    }

    @Test
    fun cancelledCommandsAreNeitherSentNorUnresolvedWork() {
        val company =
            f.base.queue.record.copy(revision = 0, state = CollaborationMessageState.Cancelled)
        val space = f.record.copy(revision = 0, state = CollaborationMessageState.Cancelled)
        val report =
            collaborationMixedDeliveryReport(
                listOf(company),
                listOf(space),
                CollaborationQueueDeliveryReason.DRAINED,
            )
        assertTrue(report.confirmed.isEmpty())
        assertTrue(report.unresolved.isEmpty())
        assertEquals(CollaborationQueueDeliveryReason.DRAINED, report.nativeReason)
    }

    @Test
    fun originalHistoryMustMatchSenderLogicalIdHashAndUneditedText() {
        val change =
            CollaborationHistoryChange.Text(
                "\$history",
                f.original.origin.sender,
                f.record.intent.id,
                100,
                f.record.intent.text,
                f.original.origin.contentHash,
            )
        assertEquals(
            listOf("\$history"),
            assertNotNull(
                    collaborationSpaceMessageHistoryReceipt(f.access, f.record, listOf(change))
                )
                .eventIds,
        )
        for (other in
            listOf(
                change.copy(sender = "@other:matrix.example.test"),
                change.copy(logicalId = f.base.queue.id(993)),
                change.copy(contentHash = "b".repeat(64)),
                change.copy(text = "replacement"),
            )) {
            assertNull(collaborationSpaceMessageHistoryReceipt(f.access, f.record, listOf(other)))
        }
    }

    @Test
    fun contradictoryOriginalHistoryAliasesDoNotConfirmTheMessage() {
        val first =
            CollaborationHistoryChange.Text(
                "\$history",
                f.original.origin.sender,
                f.record.intent.id,
                100,
                f.record.intent.text,
                f.original.origin.contentHash,
            )
        val second = first.copy(eventId = "\$conflict", contentHash = "b".repeat(64))
        assertNull(
            collaborationSpaceMessageHistoryReceipt(f.access, f.record, listOf(first, second))
        )
        assertNull(collaborationSpaceMessageHistoryReceipt(f.access, f.record, emptyList()))
        assertNull(
            collaborationSpaceMessageHistoryReceipt(
                f.access,
                f.record,
                (1..129).map { first.copy(eventId = "\$event-$it") },
            )
        )
    }
}
