package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationMessageRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.recoverCollaborationQueue
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.*
import kotlin.time.Clock

/** Fictional proof: deliberately die after native commit and before publishing its app mapping. */
internal class HrisProductQueueProof(
    context: Context,
    private val runtime: AndroidCollaborationRuntimeLease,
    private val roomId: String,
    private val conversationId: String,
) {
    private val database = createAppDatabase(context)
    private val key = AndroidCredentialStore(context, "collaboration-proof-command-content-key")
    private val messages =
        EncryptedCollaborationMessageRepository(database.commands, AesGcmContentCipher(), key)
    private val access = runtime.owner.localAccess()
    private var writer: CollaborationMessageQueueLease? = null

    private suspend fun acquire(): CollaborationMessageQueueLease {
        check(writer == null)
        database.accounts.activate(
            access.sessionId,
            accountStorageKey(access.audience, access.accountId),
        )
        val acquired = success(runtime.acquireMessageQueue(roomId))
        writer = acquired
        val competitor = runtime.acquireMessageQueue(roomId)
        check(
            competitor is AppResult.Failed &&
                competitor.failure.code == "client.collaboration_queue_busy"
        )
        return acquired
    }

    suspend fun stage(logicalId: String, text: String): String {
        val queue = acquire()
        val intent =
            CollaborationMessageIntent(
                logicalId,
                access.messageOwner(),
                conversationId,
                Clock.System.now(),
                text,
            )
        check(success(messages.insert(access, intent)) == CollaborationMessageInsertion.STORED)
        val queued = requireNotNull(success(messages.get(access, logicalId)))
        val before = success(queue.snapshot())
        check(before.isEmpty())
        val attempt = CollaborationMessageState.Staging(queue.device, before)
        check(success(messages.update(access, queued, attempt)))
        success(queue.enqueue(logicalId, text))
        val recovered = recoverCollaborationQueue(attempt, queue.device, success(queue.snapshot()))
        check(recovered is CollaborationQueueRecovery.Found)
        // Keep Staging durable, with no native transaction mapping. The driver now kills us.
        check(requireNotNull(success(messages.get(access, logicalId))).state == attempt)
        return recovered.transactionId
    }

    suspend fun recover(logicalId: String, text: String, expectedTransaction: String) {
        val queue = acquire()
        val record = requireNotNull(success(messages.get(access, logicalId)))
        check(
            record.intent.owner == access.messageOwner() &&
                record.intent.conversationId == conversationId &&
                record.intent.text == text
        )
        val attempt = record.state as CollaborationMessageState.Staging
        check(record.revision == 1L)
        val recovered = recoverCollaborationQueue(attempt, queue.device, success(queue.snapshot()))
        check(recovered == CollaborationQueueRecovery.Found(expectedTransaction))
        val staged = CollaborationMessageState.Staged(queue.device, expectedTransaction)
        check(success(messages.update(access, record, staged)))
        check(requireNotNull(success(messages.get(access, logicalId))).state == staged)
        success(queue.close())
        writer = null
        check(queue.snapshot() is AppResult.Failed)
        val another = success(runtime.acquireMessageQueue(roomId))
        try {
            check(success(another.snapshot()) == setOf(expectedTransaction))
        } finally {
            success(another.close())
        }
    }

    suspend fun accepted(logicalId: String, transaction: String, eventId: String) {
        val record = requireNotNull(success(messages.get(access, logicalId)))
        val staged = record.state as CollaborationMessageState.Staged
        check(staged.transactionId == transaction)
        check(
            success(
                messages.update(
                    access,
                    record,
                    CollaborationMessageState.Sent(staged.device, transaction, eventId),
                )
            )
        )
    }

    suspend fun close() {
        try {
            writer?.let { success(it.close()) }
        } finally {
            database.close()
            key.close()
        }
    }

    private fun CollaborationOwner.localAccess() =
        CollaborationLocalAccess(sessionId, audience, accountId, companyId)

    private fun <T> success(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
