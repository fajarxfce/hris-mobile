package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.AppResult

/** Local edit state only; the existing thread fixture remains an original-only native sender. */
internal class ChatThreadEditFixture(private val thread: ChatThreadFixture) {
    var beforeTarget: suspend () -> Unit = {}
    var beforeEvidence: suspend () -> Unit = {}
    var unavailable = false
    val facts = mutableMapOf<String, List<CollaborationEditEvidence>>()
    val archived = mutableMapOf<String, CollaborationMessageCompletion>()

    val targets =
        object : CollaborationEditTargetRepository {
            override suspend fun getEditCandidate(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationEditCandidate?> {
                beforeTarget()
                if (
                    unavailable ||
                        roomId != thread.source.roomId ||
                        conversationId != thread.conversationId
                )
                    return AppResult.Success(null)
                val message =
                    thread.historyRows.value.singleOrNull {
                        it.primaryEventId == eventId && !it.redacted && !it.locallyHidden
                    } ?: return AppResult.Success(null)
                return AppResult.Success(
                    CollaborationEditCandidate(
                        CollaborationEditTarget(
                            roomId,
                            message.primaryEventId,
                            message.logicalId,
                            message.sender,
                            message.contentHash,
                            message.timestamp,
                            message.firstAcceptedAt ?: message.timestamp,
                            message.replyToEventId,
                            message.forwarded,
                        ),
                        requireNotNull(message.latestEdit?.edit?.text ?: message.text),
                    )
                )
            }
        }

    val evidence =
        object : CollaborationEditEvidenceRepository {
            override suspend fun getEditEvidence(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationEditEvidence?> = error("Operation evidence only")

            override suspend fun getEditOperationEvidence(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                operationId: String,
            ): AppResult<List<CollaborationEditEvidence>> {
                beforeEvidence()
                return AppResult.Success(facts[operationId].orEmpty())
            }
        }

    val completions =
        object : CollaborationMessageCompletionRepository {
            override suspend fun readCompletion(access: CollaborationLocalAccess, id: String) =
                AppResult.Success(archived[id])

            override suspend fun archiveCompletion(
                access: CollaborationLocalAccess,
                completion: CollaborationMessageCompletion,
            ): AppResult<Boolean> {
                val previous = archived[completion.record.intent.id]
                if (previous != null) return AppResult.Success(previous == completion)
                if (completion.record !in thread.records.value) return AppResult.Success(false)
                archived[completion.record.intent.id] = completion
                thread.records.value =
                    thread.records.value.filterNot { it.intent.id == completion.record.intent.id }
                return AppResult.Success(true)
            }
        }

    val retirement =
        object : CollaborationReceiptRetirementRepository {
            override suspend fun removeReceipts(
                access: CollaborationLocalAccess,
                conversationId: String,
                expected: List<CollaborationMessageReceipt>,
            ): AppResult<Boolean> {
                if (expected.any { it !in thread.receiptRows }) return AppResult.Success(false)
                thread.receiptRows.removeAll(expected.toSet())
                return AppResult.Success(true)
            }
        }
}
