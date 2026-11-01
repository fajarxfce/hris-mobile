package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationMessageQueueSource
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
import dev.fajar.starter.common.result.*

internal class AndroidCollaborationMessageQueueLease(
    override val owner: CollaborationOwner,
    override val device: CollaborationMessageDevice,
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
            safeAndroidCollaborationCall { source.snapshot() }
        }

    override suspend fun enqueue(logicalId: String, text: String): AppResult<Unit> {
        if (!validCollaborationMessageId(logicalId) || !validCollaborationText(text))
            return collaborationSetupFailure(
                "client.collaboration_invalid_message",
                FailureKind.Validation,
            )
        return operations.execute {
            failure()?.let {
                return@execute AppResult.Failed(it)
            }
            safeAndroidCollaborationCall { source.enqueue(logicalId, text) }
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
            safeAndroidCollaborationCall { source.resume(mapped) }
        }
    }

    override suspend fun close() = child.close()

    override fun toString() = "AndroidCollaborationMessageQueueLease([redacted])"
}
