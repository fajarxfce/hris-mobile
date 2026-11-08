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
 * One explicit admitted staging attempt. The SDK remains paused until separate delivery admission.
 */
class StageCollaborationText(
    private val messages: CollaborationMessageRepository,
    private val lists: CollaborationListRepository,
    private val transport: CollaborationTransportRepository,
    private val runtimes: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        deviceId: String,
        messageId: String,
    ): AppResult<CollaborationMessageRecord> =
        invoke(CollaborationOperationScope.Selected(workspaceId), deviceId, messageId)

    suspend operator fun invoke(
        scope: CollaborationOperationScope,
        deviceId: String,
        messageId: String,
    ): AppResult<CollaborationMessageRecord> =
        execution.withLock {
            if (
                !validCollaborationMessageId(deviceId) ||
                    !collaborationOperationOwnsDevice(scope, deviceId) ||
                    !validCollaborationMessageId(messageId)
            )
                return@withLock failed(
                    "client.collaboration_invalid_message",
                    FailureKind.Validation,
                )
            val captured = capture(scope)
            if (captured is AppResult.Failed) return@withLock captured
            val owner = (captured as AppResult.Success).value
            continuation(scope, owner, null).let { if (it is AppResult.Failed) return@withLock it }
            val read = checked(scope, owner) { messages.get(owner.localListAccess(), messageId) }
            if (read is AppResult.Failed) return@withLock read
            val record =
                (read as AppResult.Success).value
                    ?: return@withLock failed("client.collaboration_message_changed")
            if (
                record.intent.id != messageId ||
                    record.intent.owner != owner.localListAccess().messageOwner()
            )
                return@withLock failed("client.collaboration_message_changed")
            if (
                record.state is CollaborationMessageState.Staged ||
                    record.state is CollaborationMessageState.Sent ||
                    record.state == CollaborationMessageState.Cancelled
            )
                return@withLock AppResult.Success(record)
            val room = admitRoom(scope, owner, deviceId, record.intent.conversationId)
            if (room is AppResult.Failed) return@withLock room
            val roomId = (room as AppResult.Success).value
            if (record.intent.editTarget?.roomId?.let { it != roomId } == true)
                return@withLock failed(
                    "client.collaboration_conversation_mismatch",
                    FailureKind.AccessDenied,
                )
            if (!validCollaborationEditRecord(record))
                return@withLock failed("client.collaboration_invalid_message")
            var queue: CollaborationMessageQueueLease? = null
            try {
                // Capture the resource before rechecking admission, so a late owner cannot orphan
                // it.
                val acquired = runtimes.acquireMessageQueue(owner, deviceId, roomId)
                if (acquired is AppResult.Success) queue = acquired.value
                continuation(scope, owner, acquired).let {
                    if (it is AppResult.Failed) return@withLock it
                }
                if (acquired is AppResult.Failed) return@withLock acquired
                val writer = requireNotNull(queue)
                if (
                    writer.owner != owner ||
                        writer.device.nativeSessionId != owner.nativeSessionId ||
                        writer.device.deviceId != deviceId ||
                        writer.device.roomId != roomId ||
                        !validCollaborationMessageDevice(writer.device)
                )
                    return@withLock failed(
                        "client.collaboration_queue_owner_changed",
                        FailureKind.Unauthorized,
                    )
                stage(scope, owner, record, writer)
            } finally {
                // Native work remains parent-owned if this bounded close cannot finish yet.
                withContext(NonCancellable) { queue?.close() }
            }
        }

    private suspend fun admitRoom(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        deviceId: String,
        conversationId: String,
    ): AppResult<String> {
        val read = checked(scope, owner) { transport.read(owner, conversationId) }
        if (read is AppResult.Failed) return read
        return collaborationTransportRoom(
            (read as AppResult.Success).value,
            owner,
            deviceId,
            conversationId,
            build.platform.name.uppercase(),
        )
    }

    private suspend fun stage(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        original: CollaborationMessageRecord,
        queue: CollaborationMessageQueueLease,
    ): AppResult<CollaborationMessageRecord> {
        val access = owner.localListAccess()
        val listed =
            checked(scope, owner) {
                messages.observe(access, original.intent.conversationId).first()
            }
        if (listed is AppResult.Failed) return listed
        val records = (listed as AppResult.Success).value
        if (
            records.size > 128 ||
                records.any {
                    it.intent.owner != access.messageOwner() ||
                        it.intent.conversationId != original.intent.conversationId ||
                        !validCollaborationMessageState(it.state)
                } ||
                records.map { it.intent.id }.distinct().size != records.size ||
                records.singleOrNull { it.intent.id == original.intent.id } != original
        )
            return failed("client.collaboration_message_changed")
        val others = records.filter { it.intent.id != original.intent.id }
        if (
            others.any {
                val state = it.state
                state is CollaborationMessageState.Staging &&
                    state.device.nativeSessionId == queue.device.nativeSessionId &&
                    state.device.deviceId == queue.device.deviceId
            }
        )
            return failed("client.collaboration_queue_unresolved")
        val current = checked(scope, owner) { queue.snapshot() }
        if (current is AppResult.Failed) return current
        val snapshot = (current as AppResult.Success).value.toSet()
        if (snapshot.size > 128 || !snapshot.all(::validCollaborationTransactionId))
            return failed("client.collaboration_queue_unresolved")
        val mapped =
            others
                .mapNotNull { collaborationRetainedTransaction(it, queue.device, snapshot) }
                .toSet()
        val retained = retainAttempt(scope, owner, original, queue, mapped, snapshot)
        if (retained is AppResult.Failed) return retained
        val pending = (retained as AppResult.Success).value
        val attempt = pending.state as CollaborationMessageState.Staging
        val recovery = recoverCollaborationQueue(attempt, queue.device, snapshot)
        val transaction =
            when (recovery) {
                is CollaborationQueueRecovery.Found -> recovery.transactionId
                CollaborationQueueRecovery.Missing -> {
                    val origin =
                        attempt.origin ?: return failed("client.collaboration_queue_unresolved")
                    val sent =
                        checked(scope, owner) {
                            queue.enqueue(
                                pending.intent.id,
                                pending.intent.text,
                                origin,
                                pending.intent.replyToEventId,
                                pending.intent.forwarded,
                                pending.intent.editTarget,
                            )
                        }
                    if (sent is AppResult.Failed) return sent
                    val after = checked(scope, owner) { queue.snapshot() }
                    if (after is AppResult.Failed) return after
                    val recovered =
                        recoverCollaborationQueue(
                            attempt,
                            queue.device,
                            (after as AppResult.Success).value,
                        )
                    if (recovered !is CollaborationQueueRecovery.Found)
                        return failed("client.collaboration_queue_unresolved")
                    recovered.transactionId
                }
                else -> return failed("client.collaboration_queue_unresolved")
            }
        if (transaction in mapped) return failed("client.collaboration_queue_unresolved")
        val state = CollaborationMessageState.Staged(queue.device, transaction, attempt.origin)
        val saved = checked(scope, owner) { messages.update(access, pending, state) }
        if (saved is AppResult.Failed) return saved
        if (!(saved as AppResult.Success).value)
            return failed("client.collaboration_message_changed")
        return AppResult.Success(pending.copy(revision = pending.revision + 1, state = state))
    }

    private suspend fun retainAttempt(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        original: CollaborationMessageRecord,
        queue: CollaborationMessageQueueLease,
        mapped: Set<String>,
        snapshot: Set<String>,
    ): AppResult<CollaborationMessageRecord> {
        return when (val state = original.state) {
            CollaborationMessageState.Queued -> {
                if (!mapped.containsAll(snapshot))
                    return failed("client.collaboration_queue_unresolved")
                if (snapshot.size == 128)
                    return failed("client.command_queue_full", FailureKind.Storage)
                val prepared =
                    checked(scope, owner) {
                        queue.prepareText(
                            original.intent.id,
                            original.intent.text,
                            original.intent.replyToEventId,
                            original.intent.forwarded,
                            original.intent.editTarget,
                        )
                    }
                if (prepared is AppResult.Failed) return prepared
                val origin = (prepared as AppResult.Success).value
                if (!validCollaborationMessageOrigin(origin))
                    return failed("client.collaboration_invalid_message_origin")
                val attempt = CollaborationMessageState.Staging(queue.device, snapshot, origin)
                val saved =
                    checked(scope, owner) {
                        messages.update(owner.localListAccess(), original, attempt)
                    }
                if (saved is AppResult.Failed) return saved
                if (!(saved as AppResult.Success).value)
                    return failed("client.collaboration_message_changed")
                AppResult.Success(original.copy(revision = original.revision + 1, state = attempt))
            }
            is CollaborationMessageState.Staging -> {
                if (state.device != queue.device)
                    return failed("client.collaboration_queue_other_device")
                if (!mapped.containsAll(state.before))
                    return failed("client.collaboration_queue_unresolved")
                AppResult.Success(original)
            }
            else -> failed("client.collaboration_message_changed")
        }
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
