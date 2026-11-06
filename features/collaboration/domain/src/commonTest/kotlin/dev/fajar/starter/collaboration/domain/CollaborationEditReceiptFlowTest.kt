package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationEditReceiptFlowTest {
    @Test
    fun rejectedProviderAcceptanceRecoversItsReceiptWithoutReadingAnOriginalAcknowledgement() =
        runTest {
            val f = EditReceiptFixture()
            f.prepare()
            assertEquals(AppResult.Success(1), f.recover())
            assertEquals(f.record.intent, f.delivery.journal.single().intent)
            assertEquals(listOf("\$edit"), f.delivery.journal.single().eventIds)
            assertEquals(0, f.base.original.reads)
            assertEquals(AppResult.Success(0), f.recover())
        }

    @Test
    fun mismatchedOperationSenderTargetOrContentNeverRepairsAReceipt() = runTest {
        for (kind in 0..4) {
            val f = EditReceiptFixture()
            f.prepare()
            val original = f.evidence.single()
            f.evidence =
                listOf(
                    when (kind) {
                        0 -> original.copy(operationId = "00000000-0000-0000-0000-000000009999")
                        1 -> original.copy(sender = "@other:test")
                        2 -> original.copy(targetEventId = "\$other")
                        3 -> original.copy(contentHash = "f".repeat(64))
                        else -> original.copy(eventId = f.target.eventId)
                    }
                )
            assertIs<AppResult.Failed>(f.recover())
            assertTrue(f.delivery.journal.isEmpty())
        }
    }

    @Test
    fun visibleRejectedEditStaysSentUntilAcknowledgedThenArchivesWithoutResending() = runTest {
        val f = EditReceiptFixture()
        f.prepare()
        assertEquals(AppResult.Success(1), f.recover())
        f.markSent()
        assertEquals(AppResult.Success(0), f.retire())
        assertEquals(listOf(f.record), f.delivery.records)
        assertEquals(AppResult.Success(1), f.retire(f.target.eventId))
        assertTrue(f.delivery.records.isEmpty())
        assertTrue(f.delivery.journal.isEmpty())
        assertEquals(f.record, f.base.completions.records.getValue(f.record.intent.id).record)
        assertEquals(AppResult.Success(0), f.retire(f.target.eventId))
    }

    @Test
    fun acceptedHiddenAndRedactedEditsDoNotLeaveUnreachableCommands() = runTest {
        for (kind in 0..2) {
            val f = EditReceiptFixture()
            f.prepare()
            when (kind) {
                0 ->
                    f.evidence =
                        f.evidence.map {
                            it.copy(eligible = true, timestamp = f.target.firstAcceptedAt + 1)
                        }
                1 -> f.visible = false
                2 -> f.evidence = f.evidence.map { it.copy(redacted = true) }
            }
            assertEquals(AppResult.Success(1), f.recover())
            f.markSent()
            assertEquals(AppResult.Success(1), f.retire())
            assertTrue(f.delivery.records.isEmpty())
            assertEquals(0, f.base.original.reads)
        }
    }

    @Test
    fun lateDenialPreventsReceiptPublicationWithoutDiscardingQueuedWork() = runTest {
        val f = EditReceiptFixture()
        f.prepare()
        f.afterEvidence = {
            f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
        }
        assertEquals(f.owner.denied, assertIs<AppResult.Failed>(f.recover()).failure)
        assertTrue(f.delivery.journal.isEmpty())
        assertEquals(listOf(f.record), f.delivery.records)
    }

    @Test
    fun cancellationDrainsAndCannotArchiveFromAnIncompleteEvidenceRead() = runTest {
        val f = EditReceiptFixture()
        f.prepare()
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        f.afterEvidence = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                released.complete(Unit)
            }
        }
        val pending = async { f.recover() }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(released.isCompleted)
        assertTrue(f.delivery.journal.isEmpty())
        assertEquals(listOf(f.record), f.delivery.records)
    }

    @Test
    fun cancellationDrainsOutcomeAndAcknowledgementReadsWithoutLosingCommands() = runTest {
        for (acknowledge in listOf(false, true)) {
            val f = EditReceiptFixture()
            f.prepare()
            if (acknowledge) {
                assertEquals(AppResult.Success(1), f.recover())
                f.markSent()
            }
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            f.afterEvidence = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    released.complete(Unit)
                }
            }
            val retained = f.delivery.journal.toList()
            val pending = async {
                if (acknowledge) f.acknowledge(listOf(f.record.intent.id)) else f.readOutcomes()
            }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(released.isCompleted)
            assertEquals(listOf(f.record), f.delivery.records)
            assertEquals(retained, f.delivery.journal)
            assertTrue(f.base.completions.records.isEmpty())
        }
    }

    @Test
    fun localOutcomesDistinguishUnobservedRejectedAndSettledEditsWithoutMutatingCommands() =
        runTest {
            val f = EditReceiptFixture()
            f.prepare()
            f.owner.setup.now += 365.days
            val retained = f.record
            fun status(result: AppResult<List<CollaborationEditOutcome>>) =
                assertIs<AppResult.Success<List<CollaborationEditOutcome>>>(result)
                    .value
                    .single()
                    .kind
            assertEquals(CollaborationEditOutcomeKind.NOT_APPLIED, status(f.readOutcomes()))
            val rejected = f.evidence
            f.evidence = emptyList()
            assertEquals(CollaborationEditOutcomeKind.AWAITING_PROVIDER, status(f.readOutcomes()))
            f.evidence =
                rejected.map { it.copy(eligible = true, timestamp = f.target.firstAcceptedAt + 1) }
            assertEquals(CollaborationEditOutcomeKind.OBSERVED, status(f.readOutcomes()))
            f.evidence = rejected.map { it.copy(redacted = true) }
            assertEquals(CollaborationEditOutcomeKind.OBSERVED, status(f.readOutcomes()))
            assertEquals(listOf(retained), f.delivery.records)
            assertTrue(f.delivery.journal.isEmpty())
        }

    @Test
    fun outcomeReadRejectsMismatchedOverflowAndLateDenialWithoutPublishingPartialResults() =
        runTest {
            val f = EditReceiptFixture()
            f.prepare()
            val expected = f.evidence
            f.evidence = expected.map { it.copy(contentHash = "d".repeat(64)) }
            assertIs<AppResult.Failed>(f.readOutcomes())
            f.evidence = (1..129).map { expected.single().copy(eventId = "\$alias-$it") }
            assertIs<AppResult.Failed>(f.readOutcomes())
            f.evidence = expected
            f.afterEvidence = {
                f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
            }
            assertEquals(f.owner.denied, assertIs<AppResult.Failed>(f.readOutcomes()).failure)
            assertEquals(listOf(f.record), f.delivery.records)
        }

    @Test
    fun visibleNewerCompletionSuppressesAnOlderUnobservedLocalEditWithoutComparingProviderClocks() =
        runTest {
            val f = EditReceiptFixture()
            f.prepare()
            val staged = f.record.state as CollaborationMessageState.Staged
            val newer =
                f.record.copy(
                    intent =
                        f.record.intent.copy(
                            id = "00000000-0000-0000-0000-000000009992",
                            createdAt = f.record.intent.createdAt + 1.milliseconds,
                        ),
                    state =
                        CollaborationMessageState.Sent(
                            staged.device,
                            "newer",
                            "\$newer",
                            staged.origin,
                        ),
                )
            f.base.completions.records[newer.intent.id] =
                CollaborationMessageCompletion(newer, listOf("\$newer"))
            val original =
                f.base.original.originals.values
                    .single()
                    .copy(
                        latestEdit =
                            CollaborationMessageRevision(
                                CollaborationMessageEdit(
                                    "\$newer",
                                    f.target.sender,
                                    f.target.eventId,
                                    f.target.firstAcceptedAt + 1,
                                    "Newer",
                                    requireNotNull(staged.origin).contentHash,
                                    newer.intent.id,
                                    f.target.originalLogicalId,
                                ),
                                f.target.firstAcceptedAt,
                            )
                    )
            f.evidence = emptyList()
            val result =
                assertIs<AppResult.Success<List<CollaborationEditOutcome>>>(
                        f.readOutcomes(listOf(original))
                    )
                    .value
                    .single()
            assertEquals(CollaborationEditOutcomeKind.SUPERSEDED, result.kind)
            assertEquals(f.record.intent.id, result.operationId)
            assertEquals(listOf(f.record), f.delivery.records)
        }

    @Test
    fun failedEditAcknowledgementRequiresCapturedAccessRoomAndExactReviewedIds() = runTest {
        val f = EditReceiptFixture()
        f.prepare()
        assertEquals(AppResult.Success(1), f.recover())
        f.markSent()
        val ids = listOf(f.record.intent.id)
        assertIs<AppResult.Failed>(f.acknowledge(ids, expected = null))
        assertIs<AppResult.Failed>(
            f.acknowledge(ids, expected = f.access.copy(accountId = "changed"))
        )
        assertIs<AppResult.Failed>(f.acknowledge(ids, room = "!other:example.test"))
        assertIs<AppResult.Failed>(f.acknowledge(ids + ids))
        assertEquals(
            AppResult.Success(0),
            f.acknowledge(listOf("00000000-0000-0000-0000-000000009999")),
        )
        assertEquals(listOf(f.record), f.delivery.records)
        assertTrue(f.base.completions.records.isEmpty())
    }

    @Test
    fun aFailureArrivingDuringAcknowledgementIsNotSilentlyDiscarded() = runTest {
        val f = EditReceiptFixture()
        f.prepare()
        assertEquals(AppResult.Success(1), f.recover())
        f.markSent()
        val sent = f.record.state as CollaborationMessageState.Sent
        val late =
            f.record.copy(
                intent = f.record.intent.copy(id = "00000000-0000-0000-0000-000000009992"),
                state = sent.copy(transactionId = "later", eventId = "\$later"),
            )
        val lateReceipt =
            CollaborationMessageReceipt(late.intent, sent.device, "later", listOf("\$later"))
        f.afterEvidence = {
            f.owner.records.value = listOf(f.record, late)
            f.delivery.journal = f.delivery.journal + lateReceipt
        }
        assertEquals(AppResult.Success(1), f.acknowledge(listOf(f.record.intent.id)))
        assertEquals(listOf(late), f.delivery.records)
        assertEquals(listOf(lateReceipt), f.delivery.journal)
        assertEquals(setOf(f.record.intent.id), f.base.completions.records.keys)
    }
}

private class EditReceiptFixture {
    val base = CollaborationRetirementFixture()
    val delivery = base.delivery
    val owner = base.base
    val access = delivery.owner.localListAccess()
    lateinit var record: CollaborationMessageRecord
    val target
        get() = requireNotNull(record.intent.editTarget)

    var evidence = emptyList<CollaborationEditEvidence>()
    var visible = true
    var afterEvidence: suspend () -> Unit = {}
    private val edits =
        object : CollaborationEditEvidenceRepository {
            override suspend fun getEditEvidence(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationEditEvidence?> = error("Operation lookup only")

            override suspend fun getEditOperationEvidence(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                operationId: String,
            ): AppResult<List<CollaborationEditEvidence>> {
                assertEquals(record.intent.owner, access.messageOwner())
                assertEquals(record.intent.conversationId, conversationId)
                assertEquals(target.roomId, roomId)
                assertEquals(target.sender, sender)
                assertEquals(record.intent.id, operationId)
                val result = evidence.toList()
                afterEvidence()
                return AppResult.Success(result)
            }
        }
    private val targets =
        object : CollaborationEditTargetRepository {
            override suspend fun getEditCandidate(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationEditCandidate?> {
                assertEquals(record.intent.owner, access.messageOwner())
                assertEquals(record.intent.conversationId, conversationId)
                assertEquals(target.roomId, roomId)
                assertEquals(target.eventId, eventId)
                return AppResult.Success(
                    CollaborationEditCandidate(target, "Original text").takeIf { visible }
                )
            }
        }
    private val recovery =
        RecoverCollaborationMessageReceipts(
            base.original.history,
            delivery.messages,
            delivery.receipts,
            delivery.lists,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
            base.completions,
            edits,
        )
    private val retirement =
        RetireCollaborationMessages(
            delivery.messages,
            base.completions,
            delivery.receipts,
            base.retirement,
            base.original.history,
            delivery.lists,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
            edits,
            targets,
        )

    private val outcomeReader =
        ReadCollaborationEditOutcomes(
            edits,
            base.completions,
            delivery.lists,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
        )

    suspend fun readOutcomes(
        originals: List<CollaborationHistoryMessage> = base.original.originals.values.toList()
    ) =
        outcomeReader(
            "selection",
            access,
            delivery.conversation,
            target.roomId,
            delivery.records,
            originals,
        )

    suspend fun acknowledge(
        ids: List<String>,
        expected: CollaborationLocalAccess? = access,
        room: String = target.roomId,
    ) =
        retirement(
            "selection",
            delivery.conversation,
            CollaborationEditAcknowledgement(room, target.eventId, ids),
            expected,
        )

    suspend fun prepare() {
        base.original.prepare()
        val original = delivery.records.single()
        val message = base.original.originals.getValue(original.intent.id)
        val target =
            CollaborationEditTarget(
                delivery.device.roomId,
                message.primaryEventId,
                message.logicalId,
                message.sender,
                message.contentHash,
                message.timestamp,
                message.timestamp,
            )
        val state =
            (original.state as CollaborationMessageState.Staged).copy(
                origin = CollaborationMessageOrigin(message.sender, "b".repeat(64))
            )
        record =
            original.copy(
                intent =
                    original.intent.copy(
                        id = "00000000-0000-0000-0000-000000009991",
                        text = "New edit text",
                        editTarget = target,
                    ),
                state = state,
            )
        owner.records.value = listOf(record)
        evidence =
            listOf(
                CollaborationEditEvidence(
                    "\$edit",
                    target.sender,
                    target.eventId,
                    target.firstAcceptedAt + 900_000,
                    "b".repeat(64),
                    record.intent.id,
                    false,
                    false,
                )
            )
    }

    fun markSent() {
        val sent = requireNotNull(collaborationReceiptState(record, delivery.journal.single()))
        record = record.copy(revision = record.revision + 1, state = sent)
        owner.records.value = listOf(record)
    }

    suspend fun recover() = recovery("selection", delivery.conversation)

    suspend fun retire(acknowledged: String? = null) =
        retirement(
            "selection",
            delivery.conversation,
            acknowledged?.let {
                CollaborationEditAcknowledgement(target.roomId, it, listOf(record.intent.id))
            },
            delivery.owner.localListAccess(),
        )
}
