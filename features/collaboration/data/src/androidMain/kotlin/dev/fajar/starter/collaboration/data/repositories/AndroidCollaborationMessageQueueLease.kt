package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationMessageQueueSource
import dev.fajar.starter.collaboration.data.dto.CollaborationQueuedOriginalDto
import dev.fajar.starter.collaboration.data.mappers.toQueuedOriginal
import dev.fajar.starter.collaboration.data.messages.collaborationOutgoingOrigin
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.RequestFailureException
import org.matrix.rustcomponents.sdk.ConfirmedQueueEventResult

internal class AndroidCollaborationMessageQueueLease(
    override val owner: CollaborationOwner,
    override val device: CollaborationMessageDevice,
    private val matrixSender: String,
    private val source: AndroidCollaborationMessageQueueSource,
    private val operations: SdkQueueOperations,
    private val child: SdkChildLease<AndroidCollaborationMessageQueueSource>,
    private val failure: () -> Failure?,
) : CollaborationMessageQueueLease {
    override suspend fun snapshot(): AppResult<Set<String>> =
        operations.execute {
            failure()?.let {
                return@execute AppResult.Failed(it)
            }
            safeAndroidCollaborationCall(AndroidCollaborationOperation.QueueSnapshot) {
                source.snapshot()
            }
        }

    override suspend fun originals(): AppResult<List<CollaborationQueuedOriginal>> =
        operations.execute {
            failure()?.let {
                return@execute AppResult.Failed(it)
            }
            safeAndroidCollaborationCall(AndroidCollaborationOperation.QueueOriginals) {
                source.originals().map { it.toQueuedOriginal(matrixSender) }
            }
        }

    override suspend fun settleConfirmed(
        original: CollaborationQueuedOriginal,
        eventId: String,
    ): AppResult<CollaborationQueueSettlement> =
        operations.execute {
            failure()?.let {
                return@execute AppResult.Failed(it)
            }
            safeAndroidCollaborationCall(AndroidCollaborationOperation.QueueSettle) {
                require(validCollaborationEventId(eventId))
                val dto =
                    CollaborationQueuedOriginalDto(
                        original.transactionId,
                        original.eventType,
                        original.serializedContent,
                    )
                require(dto.toQueuedOriginal(matrixSender) == original)
                when (source.settleConfirmed(dto, eventId)) {
                    ConfirmedQueueEventResult.SETTLED -> CollaborationQueueSettlement.Settled
                    ConfirmedQueueEventResult.MISSING -> CollaborationQueueSettlement.AlreadyAbsent
                    ConfirmedQueueEventResult.QUEUE_ENABLED,
                    ConfirmedQueueEventResult.IN_FLIGHT ->
                        throw RequestFailureException(
                            Failure(
                                FailureKind.Unavailable,
                                "Message recovery is waiting.",
                                code = "client.collaboration_queue_settlement_pending",
                            )
                        )
                    ConfirmedQueueEventResult.CONTENT_MISMATCH,
                    ConfirmedQueueEventResult.NOT_AN_EVENT,
                    ConfirmedQueueEventResult.DEPENDENT_WORK ->
                        throw RequestFailureException(
                            Failure(
                                FailureKind.Conflict,
                                "Message recovery needs its original record.",
                                code = "client.collaboration_queue_unresolved",
                            )
                        )
                }
            }
        }

    override suspend fun prepareText(
        logicalId: String,
        text: String,
        replyToEventId: String?,
        forwarded: Boolean,
        editTarget: CollaborationEditTarget?,
    ): AppResult<CollaborationMessageOrigin> {
        if (
            !validCollaborationMessageId(logicalId) ||
                !validCollaborationText(text) ||
                (replyToEventId != null && !validCollaborationReplyEventId(replyToEventId)) ||
                !editMatches(logicalId, replyToEventId, forwarded, editTarget)
        )
            return collaborationSetupFailure(
                "client.collaboration_invalid_message",
                FailureKind.Validation,
            )
        return operations.execute {
            failure()?.let {
                return@execute AppResult.Failed(it)
            }
            safeAndroidCollaborationCall(AndroidCollaborationOperation.QueueOrigin) {
                collaborationOutgoingOrigin(
                    matrixSender,
                    logicalId,
                    text,
                    replyToEventId,
                    forwarded,
                    editTarget,
                )
            }
        }
    }

    override suspend fun enqueue(
        logicalId: String,
        text: String,
        origin: CollaborationMessageOrigin,
        replyToEventId: String?,
        forwarded: Boolean,
        editTarget: CollaborationEditTarget?,
    ): AppResult<Unit> {
        if (
            !validCollaborationMessageId(logicalId) ||
                !validCollaborationText(text) ||
                (replyToEventId != null && !validCollaborationReplyEventId(replyToEventId)) ||
                !editMatches(logicalId, replyToEventId, forwarded, editTarget)
        )
            return collaborationSetupFailure(
                "client.collaboration_invalid_message",
                FailureKind.Validation,
            )
        return operations.execute {
            failure()?.let {
                return@execute AppResult.Failed(it)
            }
            val expected =
                safeAndroidCollaborationCall(AndroidCollaborationOperation.QueueMatch) {
                    collaborationOutgoingOrigin(
                        matrixSender,
                        logicalId,
                        text,
                        replyToEventId,
                        forwarded,
                        editTarget,
                    )
                }
            if (expected is AppResult.Failed) return@execute expected
            if ((expected as AppResult.Success).value != origin)
                return@execute collaborationSetupFailure(
                    "client.collaboration_message_content_changed"
                )
            safeAndroidCollaborationCall(AndroidCollaborationOperation.QueueEnqueue) {
                source.enqueue(logicalId, text, replyToEventId, forwarded, editTarget)
            }
        }
    }

    private fun editMatches(
        id: String,
        reply: String?,
        forwarded: Boolean,
        target: CollaborationEditTarget?,
    ): Boolean =
        target == null ||
            (validCollaborationEditTarget(target) &&
                target.roomId == device.roomId &&
                target.sender == matrixSender &&
                target.originalLogicalId != id &&
                target.replyToEventId == reply &&
                target.forwarded == forwarded)

    override suspend fun resume(
        mappedTransactions: Set<String>
    ): AppResult<CollaborationQueueDelivery> {
        val mapped = mappedTransactions.toSet()
        if (mapped.size > 128 || !mapped.all(::validCollaborationTransactionId))
            return collaborationSetupFailure(
                "client.collaboration_invalid_queue",
                FailureKind.Validation,
            )
        return operations.execute(waitMillis = 25_000) {
            failure()?.let {
                return@execute AppResult.Failed(it)
            }
            safeAndroidCollaborationCall(AndroidCollaborationOperation.QueueResume) {
                source.resume(mapped)
            }
        }
    }

    override suspend fun close() = child.close()

    override fun toString() = "AndroidCollaborationMessageQueueLease([redacted])"
}
