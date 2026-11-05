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
 * One live-admitted room window. Its application lifetime owns invocation; no transport retries.
 */
class DeliverCollaborationMessages(
    private val messages: CollaborationMessageRepository,
    private val receipts: CollaborationReceiptRepository,
    private val lists: CollaborationListRepository,
    private val conversations: CollaborationConversationRepository,
    private val setup: CollaborationSetupRepository,
    private val runtimes: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val completions: CollaborationMessageCompletionRepository,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        deviceId: String,
        conversationId: String,
    ): AppResult<CollaborationDeliveryReport> =
        invoke(CollaborationOperationScope.Selected(workspaceId), deviceId, conversationId)

    suspend operator fun invoke(
        scope: CollaborationOperationScope,
        deviceId: String,
        conversationId: String,
    ): AppResult<CollaborationDeliveryReport> =
        execution.withLock {
            if (
                !validCollaborationMessageId(deviceId) ||
                    !collaborationOperationOwnsDevice(scope, deviceId) ||
                    !validCollaborationMessageId(conversationId)
            )
                return@withLock failed(
                    "client.collaboration_invalid_message",
                    FailureKind.Validation,
                )
            val captured = capture(scope)
            if (captured is AppResult.Failed) return@withLock captured
            val owner = (captured as AppResult.Success).value
            continuation(scope, owner, null).let { if (it is AppResult.Failed) return@withLock it }
            val admitted = admitRoom(scope, owner, deviceId, conversationId)
            if (admitted is AppResult.Failed) return@withLock admitted
            val roomId = (admitted as AppResult.Success).value
            var queue: CollaborationMessageQueueLease? = null
            try {
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
                deliver(scope, owner, conversationId, writer)
            } finally {
                withContext(NonCancellable) { queue?.close() }
            }
        }

    private suspend fun deliver(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        conversationId: String,
        queue: CollaborationMessageQueueLease,
    ): AppResult<CollaborationDeliveryReport> {
        val projected = project(scope, owner, conversationId)
        if (projected is AppResult.Failed) return projected
        val records = (projected as AppResult.Success).value
        val captured = checked(scope, owner) { queue.snapshot() }
        if (captured is AppResult.Failed) return captured
        val snapshot = (captured as AppResult.Success).value.toSet()
        val mapped =
            collaborationDeliveryMappings(
                records,
                owner.localListAccess(),
                conversationId,
                queue.device,
                snapshot,
            ) ?: return failed("client.collaboration_queue_unresolved")
        if (mapped.isEmpty()) return AppResult.Success(collaborationDeliveryReport(records, null))
        val admitted = admitRoom(scope, owner, queue.device.deviceId, conversationId)
        if (admitted is AppResult.Failed) return admitted
        if ((admitted as AppResult.Success).value != queue.device.roomId)
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        val delivered = queue.resume(mapped.keys.toSet())
        if (delivered is AppResult.Failed) {
            val current = continuation(scope, owner, delivered)
            return if (current is AppResult.Failed) current else delivered
        }
        val outcome = (delivered as AppResult.Success).value
        val observed = collaborationDeliveryReceipts(outcome, mapped, queue.device, snapshot)
        val saved =
            if (observed == null) failed("client.collaboration_receipt_unresolved")
            else retainObserved(owner.localListAccess(), conversationId, observed)
        // Already observed facts use the captured account fence, even if authority has since
        // expired or the company gate closed. No new send or UI publication occurs in that step.
        val current = continuation(scope, owner, saved)
        if (current is AppResult.Failed) return current
        if (saved is AppResult.Failed) return saved
        val readmitted = admitRoom(scope, owner, queue.device.deviceId, conversationId)
        if (readmitted is AppResult.Failed) return readmitted
        if ((readmitted as AppResult.Success).value != queue.device.roomId)
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        val reconciled = project(scope, owner, conversationId)
        if (reconciled is AppResult.Failed) return reconciled
        val reason =
            if (
                outcome.reason == CollaborationQueueDeliveryReason.DRAINED &&
                    outcome.pending.isNotEmpty()
            )
                CollaborationQueueDeliveryReason.UNRESOLVED
            else outcome.reason
        return AppResult.Success(
            collaborationDeliveryReport((reconciled as AppResult.Success).value, reason)
        )
    }

    private suspend fun retainObserved(
        access: CollaborationLocalAccess,
        conversationId: String,
        observed: List<CollaborationMessageReceipt>,
    ): AppResult<Unit> =
        withContext(NonCancellable) {
            withTimeoutOrNull(5_000) {
                for (batch in observed.chunked(32)) {
                    val saved = receipts.append(access, conversationId, batch)
                    if (saved is AppResult.Failed) return@withTimeoutOrNull saved
                    if (!(saved as AppResult.Success).value)
                        return@withTimeoutOrNull failed("client.collaboration_receipt_changed")
                }
                AppResult.Success(Unit)
            } ?: failed("client.collaboration_receipt_pending", FailureKind.Storage)
        }

    private suspend fun project(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        conversationId: String,
    ): AppResult<List<CollaborationMessageRecord>> {
        val access = owner.localListAccess()
        val read = checked(scope, owner) { messages.observe(access, conversationId).first() }
        if (read is AppResult.Failed) return read
        val journal = checked(scope, owner) { receipts.read(access, conversationId) }
        if (journal is AppResult.Failed) return journal
        val records = (read as AppResult.Success).value
        val known = (journal as AppResult.Success).value
        val retained = completedReceipts(scope, owner, conversationId, records, known)
        if (retained is AppResult.Failed) return retained
        val updates =
            collaborationReceiptProjection(
                access,
                conversationId,
                records,
                known,
                (retained as AppResult.Success).value,
            ) ?: return failed("client.collaboration_receipt_unresolved")
        val next = records.associateBy { it.intent.id }.toMutableMap()
        for ((record, sent) in updates) {
            if (record.state == sent) continue
            val saved = checked(scope, owner) { messages.update(access, record, sent) }
            if (saved is AppResult.Failed) return saved
            if (!(saved as AppResult.Success).value)
                return failed("client.collaboration_message_changed")
            next[record.intent.id] = record.copy(revision = record.revision + 1, state = sent)
        }
        return AppResult.Success(records.map { next.getValue(it.intent.id) })
    }

    private suspend fun admitRoom(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        deviceId: String,
        conversationId: String,
    ): AppResult<String> {
        val registered = checked(scope, owner) { setup.read(owner) }
        if (registered is AppResult.Failed) return registered
        val device = (registered as AppResult.Success).value
        if (
            device?.id != deviceId ||
                device.state != CollaborationDeviceState.ACTIVE ||
                device.platform.name != build.platform.name.uppercase()
        )
            return failed("client.collaboration_device_unavailable", FailureKind.Unavailable)
        val read = checked(scope, owner) { conversations.get(owner, conversationId) }
        if (read is AppResult.Failed) return read
        val direct = (read as AppResult.Success).value
        if (
            direct.id != conversationId ||
                direct.companyId != owner.companyId ||
                owner.accountId !in listOf(direct.firstAccountId, direct.secondAccountId)
        )
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        val loaded = checked(scope, owner) { conversations.room(owner, conversationId) }
        if (loaded is AppResult.Failed) return loaded
        val room = (loaded as AppResult.Success).value
        if (room.conversationId != conversationId)
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        val id = room.matrixRoomId
        if (
            room.state != CollaborationDirectRoomState.READY ||
                id == null ||
                !id.startsWith('!') ||
                id.length !in 2..255 ||
                id.any { it.isWhitespace() || it.isISOControl() }
        )
            return failed("client.collaboration_room_not_ready", FailureKind.Unavailable)
        return AppResult.Success(id)
    }

    private suspend fun completedReceipts(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        conversationId: String,
        records: List<CollaborationMessageRecord>,
        journal: List<CollaborationMessageReceipt>,
    ): AppResult<List<CollaborationMessageCompletion>> {
        val access = owner.localListAccess()
        val ids =
            missingCollaborationReceiptIds(access, conversationId, records, journal)
                ?: return failed("client.collaboration_receipt_unresolved")
        val result = mutableListOf<CollaborationMessageCompletion>()
        for (id in ids) {
            val read =
                checked(scope, owner) {
                    when (val proof = completions.readCompletion(access, id)) {
                        is AppResult.Failed -> proof
                        is AppResult.Success ->
                            AppResult.Success(
                                proof.value?.let { it.copy(eventIds = it.eventIds.toList()) }
                            )
                    }
                }
            if (read is AppResult.Failed) return read
            result +=
                (read as AppResult.Success).value
                    ?: return failed("client.collaboration_receipt_unresolved")
        }
        return AppResult.Success(result.toList())
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
