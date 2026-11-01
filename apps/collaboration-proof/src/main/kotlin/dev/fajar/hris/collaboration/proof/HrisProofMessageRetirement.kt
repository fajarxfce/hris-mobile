package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationMessageRepository
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationReceiptRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationCompletionReceipt
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first

/** No owned transport: the instrumentation owns this local proof and its database providers. */
internal class HrisProofMessageRetirement(
    private val access: CollaborationLocalAccess,
    private val conversationId: String,
    private val messages: () -> EncryptedCollaborationMessageRepository,
    private val receipts: () -> EncryptedCollaborationReceiptRepository,
    private val history: () -> CollaborationHistoryRepository,
    private val lists: () -> CollaborationListRepository,
    private val app: HrisProofApplicationAccess,
    private val reopen: suspend () -> Unit,
) {
    suspend fun crash(
        logicalId: String,
        transaction: String,
        checkpoint: suspend (String) -> Unit,
    ) {
        val before = success(receipts().read(access, conversationId))
        check(before.size == 2)
        check(before.single { it.intent.id == logicalId }.transactionId == transaction)
        check(success(messages().observe(access, conversationId).first()).size == 2)
        val interrupted =
            object : CollaborationReceiptRetirementRepository {
                override suspend fun removeReceipts(
                    access: CollaborationLocalAccess,
                    conversationId: String,
                    expected: List<CollaborationMessageReceipt>,
                ): AppResult<Boolean> {
                    check(expected == before)
                    verifyRetained(before)
                    check(success(receipts().read(access, conversationId)) == before)
                    checkpoint(transaction)
                    awaitCancellation()
                }
            }
        success(retire(interrupted)(app.workspaceId, conversationId))
        error("Fixture must stop after completion, before cleanup")
    }

    suspend fun recover(logicalId: String, transaction: String): ProofQueuedMessageResult {
        val before = success(receipts().read(access, conversationId))
        check(before.size == 2)
        val ack = before.single { it.intent.id == logicalId }
        check(ack.transactionId == transaction)
        val retained = verifyRetained(before)
        val reconcile =
            ReconcileCollaborationMessages(
                messages(),
                receipts(),
                lists(),
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
                messages(),
            )
        check(success(reconcile(app.workspaceId, conversationId)) == 0)
        val recovery =
            RecoverCollaborationMessageReceipts(
                history(),
                messages(),
                receipts(),
                lists(),
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
                messages(),
            )
        check(success(recovery(app.workspaceId, conversationId)) == 0)
        check(success(retire()(app.workspaceId, conversationId)) == 2)
        check(success(retire()(app.workspaceId, conversationId)) == 0)
        check(success(receipts().read(access, conversationId)).isEmpty())
        reopen()
        check(verifyRetained(before) == retained)
        check(success(receipts().read(access, conversationId)).isEmpty())
        check(success(retire()(app.workspaceId, conversationId)) == 0)
        val sent =
            retained.single { it.record.intent.id == logicalId }.record.state
                as CollaborationMessageState.Sent
        check(sent.transactionId == transaction && sent.eventId in ack.eventIds)
        return ProofQueuedMessageResult(sent.eventId, sent.transactionId)
    }

    private suspend fun verifyRetained(
        journal: List<CollaborationMessageReceipt>
    ): List<CollaborationMessageCompletion> {
        check(success(messages().observe(access, conversationId).first()).isEmpty())
        val retained =
            journal.map { receipt ->
                val proof =
                    requireNotNull(success(messages().readCompletion(access, receipt.intent.id)))
                check(collaborationCompletionReceipt(proof) == receipt)
                check(success(messages().get(access, receipt.intent.id)) == proof.record)
                check(
                    success(messages().insert(access, receipt.intent)) ==
                        CollaborationMessageInsertion.STORED
                )
                check(
                    messages()
                        .insert(access, receipt.intent.copy(text = "Changed fictional replay"))
                        is AppResult.Failed
                )
                proof
            }
        check(success(messages().observe(access, conversationId).first()).isEmpty())
        return retained
    }

    private fun retire(retirement: CollaborationReceiptRetirementRepository = receipts()) =
        RetireCollaborationMessages(
            messages(),
            messages(),
            receipts(),
            retirement,
            history(),
            lists(),
            app.sessions,
            app.workspaces,
            app.server,
            app.build,
            app.clock,
        )

    private fun <T> success(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
