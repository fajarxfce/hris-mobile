package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.datasources.MemoryCollaborationListAdmissionDataSource
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationHistoryRepository
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationListRepository
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationMessageRepository
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationReceiptRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.recoverCollaborationQueue
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageRepository
import dev.fajar.starter.collaboration.domain.usecases.AcquireCollaborationHistory
import dev.fajar.starter.collaboration.domain.usecases.DeliverCollaborationMessages
import dev.fajar.starter.collaboration.domain.usecases.ReconcileCollaborationMessages
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.*
import kotlin.time.Clock

/** Fictional proof: deliberately die after native commit and before publishing its app mapping. */
internal class HrisProductQueueProof(
    private val context: Context,
    private val runtime: AndroidCollaborationRuntimeLease,
    private val roomId: String,
    private val conversationId: String,
    private val hrisAccessToken: String,
) {
    private var database = createAppDatabase(context)
    private val key = AndroidCredentialStore(context, "collaboration-proof-command-content-key")
    private val receiptKey =
        AndroidCredentialStore(context, "collaboration-proof-receipt-content-key")
    private val listKey = AndroidCredentialStore(context, "collaboration-proof-list-content-key")
    private val historyKey =
        AndroidCredentialStore(context, "collaboration-proof-history-content-key")
    private var application: HrisProofApplicationAccess? = null
    private var messages =
        EncryptedCollaborationMessageRepository(database.commands, AesGcmContentCipher(), key)
    private var receipts = receiptRepository()
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

    suspend fun deliver(logicalId: String, expectedTransaction: String): ProofQueuedMessageResult {
        check(writer == null)
        database.accounts.activate(
            access.sessionId,
            accountStorageKey(access.audience, access.accountId),
        )
        val app =
            HrisProofApplicationAccess(runtime.owner, hrisAccessToken).also { application = it }
        val original = requireNotNull(success(messages.get(access, logicalId)))
        val staged = original.state as CollaborationMessageState.Staged
        check(staged.transactionId == expectedTransaction)
        var interrupted = false
        val faulting =
            object : CollaborationMessageRepository by messages {
                override suspend fun update(
                    access: CollaborationLocalAccess,
                    expected: CollaborationMessageRecord,
                    state: CollaborationMessageState,
                ): AppResult<Boolean> {
                    check(state is CollaborationMessageState.Sent && expected == original)
                    interrupted = true
                    return AppResult.Failed(
                        Failure(
                            FailureKind.Storage,
                            "Fixture projection interrupted.",
                            code = "fixture.projection_interrupted",
                        )
                    )
                }
            }
        val sender =
            DeliverCollaborationMessages(
                faulting,
                receipts,
                listRepository(),
                app.conversations,
                app.setup,
                HrisProofRuntimeAccess(runtime),
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
            )
        val result = sender(app.workspaceId, staged.device.deviceId, conversationId)
        check(result is AppResult.Failed)
        if (result.failure.code != "fixture.projection_interrupted")
            throw HrisProofFailure(result.failure)
        check(interrupted)
        check(success(messages.get(access, logicalId)) == original)
        return recoverReceipt(app, original, expectedTransaction)
    }

    private suspend fun recoverReceipt(
        app: HrisProofApplicationAccess,
        original: CollaborationMessageRecord,
        expectedTransaction: String,
    ): ProofQueuedMessageResult {
        val journal = success(receipts.read(access, conversationId)).single()
        check(journal.intent == original.intent && journal.transactionId == expectedTransaction)
        check(journal.eventIds.size == 1)
        reopenDatabase()
        check(success(receipts.read(access, conversationId)) == listOf(journal))
        check(success(messages.get(access, original.intent.id)) == original)
        val reconcile =
            ReconcileCollaborationMessages(
                messages,
                receipts,
                listRepository(),
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
            )
        check(success(reconcile(app.workspaceId, conversationId)) == 1)
        check(success(reconcile(app.workspaceId, conversationId)) == 0)
        val sent = requireNotNull(success(messages.get(access, original.intent.id)))
        check(sent.intent == original.intent && sent.revision == original.revision + 1)
        val state = sent.state as CollaborationMessageState.Sent
        check(
            state.device == journal.device &&
                state.transactionId == expectedTransaction &&
                state.eventId == journal.eventIds.single()
        )
        return ProofQueuedMessageResult(state.eventId, state.transactionId)
    }

    suspend fun accepted(logicalId: String, transaction: String, eventId: String) {
        val record = requireNotNull(success(messages.get(access, logicalId)))
        val sent = record.state as CollaborationMessageState.Sent
        check(sent.transactionId == transaction && sent.eventId == eventId)
    }

    suspend fun recoverHistory(
        sentEventId: String,
        text: String,
        replyEventId: String,
        replySender: String,
        replyText: String,
    ): org.json.JSONObject {
        val app = requireNotNull(application)
        val runtimeAccess = HrisProofRuntimeAccess(runtime)
        val acquire =
            AcquireCollaborationHistory(
                EncryptedCollaborationHistoryRepository(
                    database.replicas,
                    AesGcmContentCipher(),
                    historyKey,
                ),
                listRepository(),
                app.conversations,
                app.setup,
                runtimeAccess,
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
            )
        val acquired = success(acquire(app.workspaceId, runtime.deviceId, conversationId))
        check(runtimeAccess.historyHead == replyEventId)
        val window = requireNotNull(runtimeAccess.historyWindow)
        check(
            acquired.acquiredEvents == 2 &&
                acquired.unresolvedEvents == window.unavailableEventIds.size
        )
        HrisProofProviderHistory(runtime, app, conversationId, roomId)
            .verify(setOf(sentEventId, replyEventId))
        return HrisProofOriginalHistory(
                { database.replicas },
                historyKey,
                access,
                conversationId,
                roomId,
            )
            .verify(
                window,
                listOf(
                    Triple(sentEventId, runtime.nativeClient.userId(), text),
                    Triple(replyEventId, replySender, replyText),
                ),
                ::reopenDatabase,
            )
            .put("commonHistoryAcquisitionOwned", true)
            .put("latestHistoryHeadOwned", true)
            .put("providerHistoryPageOwned", true)
    }

    private suspend fun reopenDatabase() {
        database.close()
        database = createAppDatabase(context)
        database.accounts.activate(
            access.sessionId,
            accountStorageKey(access.audience, access.accountId),
        )
        messages =
            EncryptedCollaborationMessageRepository(database.commands, AesGcmContentCipher(), key)
        receipts = receiptRepository()
    }

    suspend fun close() {
        var first: Exception? = null
        val operations: List<suspend () -> Unit> =
            listOf(
                { writer?.let { success(it.close()) } },
                { application?.close() },
                { database.close() },
                { key.close() },
                { receiptKey.close() },
                { listKey.close() },
                { historyKey.close() },
            )
        for (operation in operations) try {
            operation()
        } catch (failure: Exception) {
            if (first == null) first = failure else first.addSuppressed(failure)
        }
        first?.let { throw it }
    }

    private fun receiptRepository() =
        EncryptedCollaborationReceiptRepository(
            database.replicas,
            AesGcmContentCipher(),
            receiptKey,
        )

    private fun listRepository() =
        EncryptedCollaborationListRepository(
            database.replicas,
            AesGcmContentCipher(),
            listKey,
            MemoryCollaborationListAdmissionDataSource(),
        )

    private fun CollaborationOwner.localAccess() =
        CollaborationLocalAccess(sessionId, audience, accountId, companyId)

    private fun <T> success(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
