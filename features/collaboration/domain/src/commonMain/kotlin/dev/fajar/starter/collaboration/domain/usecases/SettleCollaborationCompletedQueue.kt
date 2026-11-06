package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Closes archived, confirmed native originals under an already admitted timeline. The caller owns
 * that observer. Current account/company and SDK fences are rechecked; no room metadata is fetched.
 */
class SettleCollaborationCompletedQueue(
    private val messages: CollaborationMessageRepository,
    private val completions: CollaborationMessageCompletionRepository,
    private val lists: CollaborationListRepository,
    private val runtimes: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        scope: CollaborationOperationScope,
        conversationId: String,
        timeline: CollaborationTimelineLease,
    ): AppResult<Int> =
        execution.withLock {
            if (
                !validCollaborationMessageDevice(timeline.device) ||
                    !validCollaborationMessageId(conversationId) ||
                    !collaborationOperationOwnsDevice(scope, timeline.deviceId)
            )
                return@withLock failed(
                    "client.collaboration_invalid_message",
                    FailureKind.Validation,
                )
            val captured = capture(scope)
            if (captured is AppResult.Failed) return@withLock captured
            val owner = (captured as AppResult.Success).value
            continuation(scope, owner, null).let { if (it is AppResult.Failed) return@withLock it }
            if (
                timeline.owner != owner ||
                    timeline.device.nativeSessionId != owner.nativeSessionId ||
                    timeline.device.deviceId != timeline.deviceId ||
                    timeline.device.roomId != timeline.matrixRoomId
            )
                return@withLock failed(
                    "client.collaboration_queue_owner_changed",
                    FailureKind.Unauthorized,
                )
            var queue: CollaborationMessageQueueLease? = null
            try {
                val acquired =
                    runtimes.acquireMessageQueue(owner, timeline.deviceId, timeline.matrixRoomId)
                if (acquired is AppResult.Success) queue = acquired.value
                continuation(scope, owner, acquired).let {
                    if (it is AppResult.Failed) return@withLock it
                }
                if (acquired is AppResult.Failed) return@withLock acquired
                val writer = requireNotNull(queue)
                if (
                    writer.owner != owner ||
                        writer.device != timeline.device ||
                        !validCollaborationMessageDevice(writer.device)
                )
                    return@withLock failed(
                        "client.collaboration_queue_owner_changed",
                        FailureKind.Unauthorized,
                    )
                settle(scope, owner, conversationId, writer)
            } finally {
                withContext(NonCancellable) { queue?.close() }
            }
        }

    private suspend fun settle(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        conversationId: String,
        queue: CollaborationMessageQueueLease,
    ): AppResult<Int> {
        val access = owner.localListAccess()
        val read = checked(scope, owner) { messages.observe(access, conversationId).first() }
        if (read is AppResult.Failed) return read
        val records = (read as AppResult.Success).value
        if (
            records.size > 128 ||
                records.map { it.intent.id }.distinct().size != records.size ||
                records.any {
                    it.intent.owner != access.messageOwner() ||
                        it.intent.conversationId != conversationId ||
                        !validCollaborationEditRecord(it) ||
                        !validCollaborationMessageState(it.state)
                }
        )
            return failed("client.collaboration_message_changed")
        val captured = checked(scope, owner) { queue.snapshot() }
        if (captured is AppResult.Failed) return captured
        val snapshot = (captured as AppResult.Success).value.toSet()
        if (snapshot.size > 128 || !snapshot.all(::validCollaborationTransactionId))
            return failed("client.collaboration_queue_unresolved")
        val mapped =
            records
                .mapNotNull { collaborationRetainedTransaction(it, queue.device, snapshot) }
                .toSet()
        // Leave a uniquely recoverable uncertain enqueue to its original staging use case.
        val uncertain =
            records
                .mapNotNull { record ->
                    val attempt =
                        record.state as? CollaborationMessageState.Staging ?: return@mapNotNull null
                    if (!mapped.containsAll(attempt.before)) return@mapNotNull null
                    (recoverCollaborationQueue(attempt, queue.device, snapshot)
                            as? CollaborationQueueRecovery.Found)
                        ?.transactionId
                }
                .toSet()
        val unknown = snapshot - mapped - uncertain
        if (unknown.isEmpty()) return AppResult.Success(0)
        val raw = checked(scope, owner) { queue.originals() }
        if (raw is AppResult.Failed) return raw
        val originals = (raw as AppResult.Success).value.toList()
        if (
            originals.size != snapshot.size ||
                originals.any { !validCollaborationQueuedOriginal(it) } ||
                originals.map { it.transactionId }.toSet() != snapshot ||
                originals.sumOf { it.serializedContent.encodeToByteArray().size } > 1_048_576
        )
            return failed("client.collaboration_queue_unresolved")
        val confirmed = mutableListOf<Pair<CollaborationQueuedOriginal, String>>()
        for (original in originals.filter { it.transactionId in unknown }) {
            val logicalId = original.logicalId ?: continue
            val proof = checked(scope, owner) { completions.readCompletion(access, logicalId) }
            if (proof is AppResult.Failed) return proof
            val completion = (proof as AppResult.Success).value ?: continue
            val event =
                collaborationQueueCompletionEvent(
                    access,
                    conversationId,
                    queue.device,
                    original,
                    completion,
                ) ?: return failed("client.collaboration_receipt_unresolved")
            // An uncertain enqueue still owns its original before-snapshot. Do not rewrite or
            // remove a referenced entry until that attempt has acquired its durable mapping.
            if (
                records.any {
                    (it.state as? CollaborationMessageState.Staging)
                        ?.before
                        ?.contains(original.transactionId) == true
                }
            )
                return failed("client.collaboration_queue_unresolved")
            confirmed += original to event
        }
        for ((original, event) in confirmed) {
            val current = checked(scope, owner) { messages.observe(access, conversationId).first() }
            if (current is AppResult.Failed) return current
            if ((current as AppResult.Success).value != records)
                return failed("client.collaboration_message_changed")
            val settled = checked(scope, owner) { queue.settleConfirmed(original, event) }
            if (settled is AppResult.Failed) return settled
        }
        if (confirmed.isNotEmpty()) {
            val after = checked(scope, owner) { queue.snapshot() }
            if (after is AppResult.Failed) return after
            if (
                (after as AppResult.Success).value !=
                    snapshot - confirmed.map { it.first.transactionId }.toSet()
            )
                return failed("client.collaboration_queue_unresolved")
        }
        return AppResult.Success(confirmed.size)
    }

    private suspend fun <T> checked(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        operation: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val result = operation()
        val admitted = continuation(scope, owner, result)
        return if (admitted is AppResult.Failed) admitted else result
    }

    private suspend fun continuation(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        received: AppResult<*>?,
    ): AppResult<Unit> {
        val rejected = (received as? AppResult.Failed)?.failure
        if (rejected != null && collaborationContentResponseClosesRead(rejected))
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { lists.restrict(owner.localListAccess(), rejected) }
            }
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(owner.localListAccess())
        val current = capture(scope)
        currentCoroutineContext().ensureActive()
        if (rejected != null && collaborationContentResponseClosesRead(rejected))
            return AppResult.Failed(rejected)
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        val admitted = requireCollaborationOwner(current, owner, received)
        if (admitted is AppResult.Failed) return admitted
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private suspend fun capture(scope: CollaborationOperationScope) =
        sessions.captureCollaborationOwner(scope, workspaces, server, build, clock)

    private fun failed(code: String, kind: FailureKind = FailureKind.Conflict) =
        collaborationSetupFailure(code, kind)
}
