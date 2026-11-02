package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.RetireCollaborationMessages
import dev.fajar.starter.common.result.*

internal class CollaborationRetirementFixture {
    val original = OriginalReceiptFixture()
    val delivery = original.delivery
    val base = delivery.base
    val completions = delivery.completions
    var removals = 0
    var rejectRemoval = false
    var removalFailure: Failure? = null
    var beforeRemove: suspend () -> Unit = {}
    var afterRemove: suspend () -> Unit = {}
    val retirement =
        object : CollaborationReceiptRetirementRepository {
            override suspend fun removeReceipts(
                access: CollaborationLocalAccess,
                conversationId: String,
                expected: List<CollaborationMessageReceipt>,
            ): AppResult<Boolean> {
                removals++
                beforeRemove()
                if (rejectRemoval || expected.any { it !in delivery.journal })
                    return AppResult.Success(false)
                val ids = expected.map { it.intent.id }.toSet()
                delivery.journal = delivery.journal.filterNot { it.intent.id in ids }
                afterRemove()
                return removalFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }
    private val useCase =
        RetireCollaborationMessages(
            delivery.messages,
            completions,
            delivery.receipts,
            retirement,
            original.history,
            delivery.lists,
            base.setup.sessions,
            base.setup.workspaces,
            base.server,
            base.build,
            base.clock,
        )

    suspend fun prepare(count: Int = 1) {
        original.prepare(count)
        base.records.value =
            delivery.records.map { record ->
                val staged = record.state as CollaborationMessageState.Staged
                record.copy(
                    revision = record.revision + 1,
                    state =
                        CollaborationMessageState.Sent(
                            staged.device,
                            staged.transactionId,
                            original.originals.getValue(record.intent.id).primaryEventId,
                            staged.origin,
                        ),
                )
            }
        delivery.journal =
            delivery.records.map { record ->
                val sent = record.state as CollaborationMessageState.Sent
                CollaborationMessageReceipt(
                    record.intent,
                    sent.device,
                    sent.transactionId,
                    listOf(sent.eventId),
                )
            }
    }

    suspend fun retire(
        scope: CollaborationLocalScope = CollaborationLocalScope.Selected("selection")
    ) = useCase(scope, delivery.conversation)
}
