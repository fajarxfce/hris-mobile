package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationMessageQueueSource
import dev.fajar.starter.collaboration.data.messages.collaborationTextOrigin
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
import dev.fajar.starter.common.result.*

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

    override suspend fun prepareText(
        logicalId: String,
        text: String,
        replyToEventId: String?,
        forwarded: Boolean,
    ): AppResult<CollaborationMessageOrigin> {
        if (
            !validCollaborationMessageId(logicalId) ||
                !validCollaborationText(text) ||
                (replyToEventId != null && !validCollaborationReplyEventId(replyToEventId))
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
                collaborationTextOrigin(matrixSender, logicalId, text, replyToEventId, forwarded)
            }
        }
    }

    override suspend fun enqueue(
        logicalId: String,
        text: String,
        origin: CollaborationMessageOrigin,
        replyToEventId: String?,
        forwarded: Boolean,
    ): AppResult<Unit> {
        if (
            !validCollaborationMessageId(logicalId) ||
                !validCollaborationText(text) ||
                (replyToEventId != null && !validCollaborationReplyEventId(replyToEventId))
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
                    collaborationTextOrigin(
                        matrixSender,
                        logicalId,
                        text,
                        replyToEventId,
                        forwarded,
                    )
                }
            if (expected is AppResult.Failed) return@execute expected
            if ((expected as AppResult.Success).value != origin)
                return@execute collaborationSetupFailure(
                    "client.collaboration_message_content_changed"
                )
            safeAndroidCollaborationCall(AndroidCollaborationOperation.QueueEnqueue) {
                source.enqueue(logicalId, text, replyToEventId, forwarded)
            }
        }
    }

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
