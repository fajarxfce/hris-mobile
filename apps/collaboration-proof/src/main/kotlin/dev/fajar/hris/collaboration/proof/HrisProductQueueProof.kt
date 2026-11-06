package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.datasources.MemoryCollaborationListAdmissionDataSource
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationHistoryRepository
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationHistoryTraversalRepository
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationListRepository
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationMessageRepository
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationReadStateRepository
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationReceiptRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.recoverCollaborationQueue
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationReceiptRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeRepository
import dev.fajar.starter.collaboration.domain.usecases.AcquireCollaborationHistory
import dev.fajar.starter.collaboration.domain.usecases.AcquireCollaborationProviderHistory
import dev.fajar.starter.collaboration.domain.usecases.DeliverCollaborationMessages
import dev.fajar.starter.collaboration.domain.usecases.ReconcileCollaborationHistoryReferences
import dev.fajar.starter.collaboration.domain.usecases.ReconcileCollaborationMessages
import dev.fajar.starter.collaboration.domain.usecases.RecoverCollaborationMessageReceipts
import dev.fajar.starter.collaboration.domain.usecases.SettleCollaborationCompletedQueue
import dev.fajar.starter.collaboration.domain.usecases.StageCollaborationText
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.*
import kotlin.time.Clock
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first

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
        val origin = success(queue.prepareText(logicalId, text))
        val attempt = CollaborationMessageState.Staging(queue.device, before, origin)
        check(success(messages.update(access, queued, attempt)))
        success(queue.enqueue(logicalId, text, origin))
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
        val staged =
            CollaborationMessageState.Staged(queue.device, expectedTransaction, attempt.origin)
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
                messages,
            )
        val result = sender(app.workspaceId, staged.device.deviceId, conversationId)
        check(result is AppResult.Failed)
        if (result.failure.code != "fixture.projection_interrupted")
            throw HrisProofFailure(result.failure)
        check(interrupted)
        check(success(messages.get(access, logicalId)) == original)
        return recoverReceipt(app, original, expectedTransaction)
    }

    suspend fun crashAfterAcknowledgement(
        logicalId: String,
        text: String,
        replyToEventId: String,
        retainConfirmedNativeQueue: Boolean,
        acknowledged: suspend (String, String) -> Unit,
    ) {
        val app = prepareAckApplication()
        val intent =
            CollaborationMessageIntent(
                logicalId,
                access.messageOwner(),
                conversationId,
                Clock.System.now(),
                text,
                replyToEventId,
            )
        check(success(messages.insert(access, intent)) == CollaborationMessageInsertion.STORED)
        val stage =
            StageCollaborationText(
                messages,
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
        val record = success(stage(app.workspaceId, runtime.deviceId, logicalId))
        val state = record.state as CollaborationMessageState.Staged
        check(requireNotNull(state.origin).sender == runtime.nativeClient.userId())
        if (retainConfirmedNativeQueue) {
            val queue = success(runtime.acquireMessageQueue(roomId))
            val original =
                try {
                    check(success(queue.snapshot()) == setOf(state.transactionId))
                    success(queue.originals()).single().also {
                        check(it.transactionId == state.transactionId)
                        check(it.logicalId == logicalId && it.origin == state.origin)
                    }
                } finally {
                    success(queue.close())
                }
            // Test-only construction of an accepted event beside its paused native original.
            // sendRaw encrypts the exact content but owns a different HTTP transaction; this
            // establishes the recovery postcondition, not a literal lost-response simulation.
            val eventId =
                requireNotNull(runtime.nativeClient.getRoom(roomId)).use { room ->
                    room.sendRaw(original.eventType, original.serializedContent)
                }
            val retained = success(runtime.acquireMessageQueue(roomId))
            try {
                check(success(retained.snapshot()) == setOf(state.transactionId))
                check(success(retained.originals()) == listOf(original))
            } finally {
                success(retained.close())
            }
            check(success(receipts.read(access, conversationId)).none { it.intent.id == logicalId })
            check(success(messages.get(access, logicalId)) == record)
            acknowledged(state.transactionId, eventId)
            awaitCancellation()
        }
        val withheld =
            object : CollaborationReceiptRepository by receipts {
                override suspend fun append(
                    access: CollaborationLocalAccess,
                    conversationId: String,
                    receipts: List<CollaborationMessageReceipt>,
                ): AppResult<Boolean> {
                    val receipt = receipts.single()
                    check(
                        receipt.intent == intent &&
                            receipt.transactionId == state.transactionId &&
                            receipt.eventIds.size == 1
                    )
                    check(
                        success(this@HrisProductQueueProof.receipts.read(access, conversationId))
                            .none { it.intent.id == logicalId }
                    )
                    check(success(messages.get(access, logicalId)) == record)
                    // No ACK event ID or body is written to the checkpoint. The driver kills this
                    // process while this receipt write is suspended under the sender's bounded
                    // wait.
                    acknowledged(state.transactionId, receipt.eventIds.single())
                    awaitCancellation()
                }
            }
        val sender =
            DeliverCollaborationMessages(
                messages,
                withheld,
                listRepository(),
                app.conversations,
                app.setup,
                HrisProofRuntimeAccess(runtime),
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
                messages,
            )
        val result = sender(app.workspaceId, runtime.deviceId, conversationId)
        if (result is AppResult.Failed) throw HrisProofFailure(result.failure)
        error("ACK crash fixture was not interrupted")
    }

    suspend fun recoverAcknowledgement(
        logicalId: String,
        text: String,
        expectedTransaction: String,
        replyToEventId: String,
        retainConfirmedNativeQueue: Boolean,
    ): ProofQueuedMessageResult {
        val app = prepareAckApplication()
        val original = requireNotNull(success(messages.get(access, logicalId)))
        val staged = original.state as CollaborationMessageState.Staged
        check(
            staged.transactionId == expectedTransaction &&
                staged.origin != null &&
                original.intent.text == text &&
                original.intent.replyToEventId == replyToEventId
        )
        check(success(receipts.read(access, conversationId)).none { it.intent.id == logicalId })
        val queue = success(runtime.acquireMessageQueue(roomId))
        try {
            if (retainConfirmedNativeQueue) {
                check(success(queue.snapshot()) == setOf(expectedTransaction))
                val retained = success(queue.originals()).single()
                check(retained.transactionId == expectedTransaction)
                check(retained.logicalId == logicalId && retained.origin == staged.origin)
            } else check(success(queue.snapshot()).isEmpty())
        } finally {
            success(queue.close())
        }
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
                HrisProofRuntimeAccess(runtime),
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
                EncryptedCollaborationHistoryRepository(
                    database.replicas,
                    AesGcmContentCipher(),
                    historyKey,
                ),
            )
        check(
            success(acquire(app.workspaceId, runtime.deviceId, conversationId)).acquiredEvents >= 1
        )
        val recovery =
            RecoverCollaborationMessageReceipts(
                EncryptedCollaborationHistoryRepository(
                    database.replicas,
                    AesGcmContentCipher(),
                    historyKey,
                ),
                messages,
                receipts,
                listRepository(),
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
                messages,
                EncryptedCollaborationHistoryRepository(
                    database.replicas,
                    AesGcmContentCipher(),
                    historyKey,
                ),
            )
        check(success(recovery(app.workspaceId, conversationId)) == 1)
        check(success(recovery(app.workspaceId, conversationId)) == 0)
        check(success(messages.get(access, logicalId)) == original)
        return recoverReceipt(app, original, expectedTransaction).also {
            check(
                requireNotNull(success(messages.get(access, logicalId))).state.origin ==
                    staged.origin
            )
        }
    }

    suspend fun crashAfterCompletion(
        logicalId: String,
        transaction: String,
        checkpoint: suspend (String) -> Unit,
    ) = retirementProof(requireNotNull(application)).crash(logicalId, transaction, checkpoint)

    suspend fun recoverCompletion(logicalId: String, transaction: String) =
        retirementProof(prepareAckApplication()).recover(logicalId, transaction)

    suspend fun settleConfirmedQueueOffline(logicalId: String, transaction: String) {
        val app = requireNotNull(application)
        check(success(messages.observe(access, conversationId).first()).isEmpty())
        val completion = requireNotNull(success(messages.readCompletion(access, logicalId)))
        check(success(messages.get(access, logicalId)) == completion.record)
        val retained = success(runtime.acquireMessageQueue(roomId))
        try {
            check(success(retained.snapshot()) == setOf(transaction))
            check(success(retained.originals()).single().logicalId == logicalId)
        } finally {
            success(retained.close())
        }
        val timeline = success(runtime.openTimeline(roomId))
        try {
            val settle =
                SettleCollaborationCompletedQueue(
                    messages,
                    messages,
                    listRepository(),
                    HrisProofRuntimeAccess(runtime),
                    app.sessions,
                    app.workspaces,
                    app.server,
                    app.build,
                    app.clock,
                )
            val scope = CollaborationOperationScope.Selected(app.workspaceId)
            check(success(settle(scope, conversationId, timeline)) == 1)
            check(success(settle(scope, conversationId, timeline)) == 0)
        } finally {
            success(timeline.close())
        }
        val settled = success(runtime.acquireMessageQueue(roomId))
        try {
            check(success(settled.snapshot()).isEmpty())
            check(success(settled.originals()).isEmpty())
        } finally {
            success(settled.close())
        }
    }

    suspend fun verifyColdOfflineHistory(
        expected: List<Pair<String, String>>,
        quotedEventId: String,
        replyToEventId: String,
    ) {
        check(application != null && writer == null && expected.size == 3)
        val history =
            EncryptedCollaborationHistoryRepository(
                database.replicas,
                AesGcmContentCipher(),
                historyKey,
            )
        val saved = success(history.observe(access, conversationId, roomId).first())
        check(
            saved.messages.single { quotedEventId in it.eventIds }.replyToEventId == replyToEventId
        )
        for ((eventId, body) in expected) {
            val original = saved.messages.single { eventId in it.eventIds }
            check(original.text == body)
            check(success(history.getByEvent(access, conversationId, roomId, eventId)) == original)
        }
    }

    private fun retirementProof(app: HrisProofApplicationAccess) =
        HrisProofMessageRetirement(
            access,
            conversationId,
            { messages },
            { receipts },
            {
                EncryptedCollaborationHistoryRepository(
                    database.replicas,
                    AesGcmContentCipher(),
                    historyKey,
                )
            },
            { listRepository() },
            app,
            ::reopenDatabase,
        )

    private suspend fun prepareAckApplication(): HrisProofApplicationAccess {
        check(writer == null && application == null)
        database.accounts.activate(
            access.sessionId,
            accountStorageKey(access.audience, access.accountId),
        )
        return HrisProofApplicationAccess(runtime.owner, hrisAccessToken).also { application = it }
    }

    private suspend fun recoverReceipt(
        app: HrisProofApplicationAccess,
        original: CollaborationMessageRecord,
        expectedTransaction: String,
    ): ProofQueuedMessageResult {
        val before = success(receipts.read(access, conversationId))
        val journal = before.single { it.intent.id == original.intent.id }
        check(journal.intent == original.intent && journal.transactionId == expectedTransaction)
        check(journal.eventIds.size == 1)
        reopenDatabase()
        check(success(receipts.read(access, conversationId)) == before)
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
                messages,
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
                EncryptedCollaborationHistoryRepository(
                    database.replicas,
                    AesGcmContentCipher(),
                    historyKey,
                ),
            )
        val acquired = success(acquire(app.workspaceId, runtime.deviceId, conversationId))
        check(runtimeAccess.historyHead == replyEventId)
        val window = requireNotNull(runtimeAccess.historyWindow)
        check(
            acquired.acquiredEvents == 2 &&
                acquired.unresolvedEvents == window.unavailableEventIds.size
        )
        HrisProofProviderHistory(
                runtime,
                roomId,
                ::acquireProviderHistory,
                ::readProviderReferences,
                ::reopenDatabase,
                ::reconcileProviderReferences,
            )
            .verify(setOf(sentEventId, replyEventId))
        readStateProof().prepare(sentEventId, replyEventId)
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
            .put("commonProviderHistoryAcquisitionOwned", true)
            .put("providerHistoryReferencesRestored", true)
            .put("providerHistoryReferencesReconciled", true)
            .put("providerHistoryRestartPreserved", true)
            .put("nativeLocalReadIntentPrepared", true)
    }

    suspend fun recoverReadState(result: org.json.JSONObject) {
        readStateProof()
            .recover(result.getString("senderEventId"), result.getString("recipientEventId"))
        result.put("nativeLocalReadIntentStoreRestored", true)
        result.put("nativeReadReceiptReplayAdmitted", true)
        result.put("nativeReadReceiptAckUncertaintyRecovered", true)
    }

    private fun readStateProof() =
        HrisProofReadState(
            runtime,
            conversationId,
            roomId,
            {
                EncryptedCollaborationReadStateRepository(
                    database.replicas,
                    AesGcmContentCipher(),
                    historyKey,
                )
            },
            {
                EncryptedCollaborationHistoryRepository(
                    database.replicas,
                    AesGcmContentCipher(),
                    historyKey,
                )
            },
            { listRepository() },
            requireNotNull(application),
            ::reopenDatabase,
        )

    suspend fun reopenHistory(
        repository: CollaborationRuntimeRepository,
        anchor: String,
        text: String,
        heldRoom: String,
        heldTransaction: String,
    ) {
        check(writer == null)
        val retained = readProviderReferences()
        HrisProofHistoryReopening(
                repository,
                runtime,
                roomId,
                conversationId,
                requireNotNull(application),
                listRepository(),
            )
            .verify(anchor, text, heldRoom, heldTransaction)
        reopenDatabase()
        check(readProviderReferences() == retained)
        check(
            reconcileProviderReferences() ==
                CollaborationHistoryReconciliation(0, retained.events.size, null)
        )
        check(readProviderReferences() == retained)
    }

    private suspend fun reconcileProviderReferences(): CollaborationHistoryReconciliation {
        val app = requireNotNull(application)
        val reconcile =
            ReconcileCollaborationHistoryReferences(
                traversalRepository(),
                EncryptedCollaborationHistoryRepository(
                    database.replicas,
                    AesGcmContentCipher(),
                    historyKey,
                ),
                listRepository(),
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
                EncryptedCollaborationHistoryRepository(
                    database.replicas,
                    AesGcmContentCipher(),
                    historyKey,
                ),
            )
        return success(
            reconcile(
                app.workspaceId,
                CollaborationHistoryTraversal(conversationId, roomId, runtime.deviceId),
            )
        )
    }

    private suspend fun acquireProviderHistory(
        runtimeAccess: HrisProofRuntimeAccess,
        origin: CollaborationHistoryPageOrigin,
    ): CollaborationProviderHistoryAcquisition {
        val app = requireNotNull(application)
        val acquire =
            AcquireCollaborationProviderHistory(
                traversalRepository(),
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
        return success(acquire(app.workspaceId, runtime.deviceId, conversationId, origin))
    }

    private suspend fun readProviderReferences() =
        success(
            traversalRepository()
                .observeReferences(
                    access,
                    CollaborationHistoryTraversal(conversationId, roomId, runtime.deviceId),
                )
                .first()
        )

    private fun traversalRepository() =
        EncryptedCollaborationHistoryTraversalRepository(
            database.replicas,
            AesGcmContentCipher(),
            historyKey,
        )

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
