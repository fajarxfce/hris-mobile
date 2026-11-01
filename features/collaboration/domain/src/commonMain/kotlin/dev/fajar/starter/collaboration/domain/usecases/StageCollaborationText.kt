package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
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
    private val conversations: CollaborationConversationRepository,
    private val setup: CollaborationSetupRepository,
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
        execution.withLock {
            if (!validCollaborationMessageId(deviceId) || !validCollaborationMessageId(messageId))
                return@withLock failed(
                    "client.collaboration_invalid_message",
                    FailureKind.Validation,
                )
            val captured = capture(workspaceId)
            if (captured is AppResult.Failed) return@withLock captured
            val owner = (captured as AppResult.Success).value
            continuation(workspaceId, owner, null).let {
                if (it is AppResult.Failed) return@withLock it
            }
            val read =
                checked(workspaceId, owner) { messages.get(owner.localListAccess(), messageId) }
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
                    record.state is CollaborationMessageState.Sent
            )
                return@withLock AppResult.Success(record)
            val room = admitRoom(workspaceId, owner, deviceId, record.intent.conversationId)
            if (room is AppResult.Failed) return@withLock room
            val roomId = (room as AppResult.Success).value
            var queue: CollaborationMessageQueueLease? = null
            try {
                // Capture the resource before rechecking admission, so a late owner cannot orphan
                // it.
                val acquired = runtimes.acquireMessageQueue(owner, deviceId, roomId)
                if (acquired is AppResult.Success) queue = acquired.value
                continuation(workspaceId, owner, acquired).let {
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
                stage(workspaceId, owner, record, writer)
            } finally {
                // Native work remains parent-owned if this bounded close cannot finish yet.
                withContext(NonCancellable) { queue?.close() }
            }
        }

    private suspend fun admitRoom(
        workspaceId: String,
        owner: CollaborationOwner,
        deviceId: String,
        conversationId: String,
    ): AppResult<String> {
        val registered = checked(workspaceId, owner) { setup.read(owner) }
        if (registered is AppResult.Failed) return registered
        val device = (registered as AppResult.Success).value
        if (
            device?.id != deviceId ||
                device.state != CollaborationDeviceState.ACTIVE ||
                device.platform.name != build.platform.name.uppercase()
        )
            return failed("client.collaboration_device_unavailable", FailureKind.Unavailable)
        val read = checked(workspaceId, owner) { conversations.get(owner, conversationId) }
        if (read is AppResult.Failed) return read
        val direct = (read as AppResult.Success).value
        if (
            direct.id != conversationId ||
                direct.companyId != owner.companyId ||
                owner.accountId !in listOf(direct.firstAccountId, direct.secondAccountId)
        )
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        val loaded = checked(workspaceId, owner) { conversations.room(owner, conversationId) }
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

    private suspend fun stage(
        workspaceId: String,
        owner: CollaborationOwner,
        original: CollaborationMessageRecord,
        queue: CollaborationMessageQueueLease,
    ): AppResult<CollaborationMessageRecord> {
        val access = owner.localListAccess()
        val listed =
            checked(workspaceId, owner) {
                messages.observe(access, original.intent.conversationId).first()
            }
        if (listed is AppResult.Failed) return listed
        val records = (listed as AppResult.Success).value
        if (
            records.size > 128 ||
                records.any {
                    it.intent.owner != access.messageOwner() ||
                        it.intent.conversationId != original.intent.conversationId
                } ||
                records.map { it.intent.id }.distinct().size != records.size ||
                records.singleOrNull { it.intent.id == original.intent.id } != original
        )
            return failed("client.collaboration_message_changed")
        val others = records.filter { it.intent.id != original.intent.id }
        if (others.any { it.state is CollaborationMessageState.Staging })
            return failed("client.collaboration_queue_unresolved")
        val mapped =
            others
                .mapNotNull { record ->
                    when (val state = record.state) {
                        is CollaborationMessageState.Staged ->
                            state.transactionId.takeIf { state.device == queue.device }
                        is CollaborationMessageState.Sent ->
                            state.transactionId.takeIf { state.device == queue.device }
                        else -> null
                    }
                }
                .toSet()
        val current = checked(workspaceId, owner) { queue.snapshot() }
        if (current is AppResult.Failed) return current
        val snapshot = (current as AppResult.Success).value.toSet()
        if (snapshot.size > 128 || !snapshot.all(::validCollaborationTransactionId))
            return failed("client.collaboration_queue_unresolved")
        val retained = retainAttempt(workspaceId, owner, original, queue, mapped, snapshot)
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
                        checked(workspaceId, owner) {
                            queue.enqueue(pending.intent.id, pending.intent.text, origin)
                        }
                    if (sent is AppResult.Failed) return sent
                    val after = checked(workspaceId, owner) { queue.snapshot() }
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
        val saved = checked(workspaceId, owner) { messages.update(access, pending, state) }
        if (saved is AppResult.Failed) return saved
        if (!(saved as AppResult.Success).value)
            return failed("client.collaboration_message_changed")
        return AppResult.Success(pending.copy(revision = pending.revision + 1, state = state))
    }

    private suspend fun retainAttempt(
        workspaceId: String,
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
                    checked(workspaceId, owner) {
                        queue.prepareText(original.intent.id, original.intent.text)
                    }
                if (prepared is AppResult.Failed) return prepared
                val origin = (prepared as AppResult.Success).value
                if (!validCollaborationMessageOrigin(origin))
                    return failed("client.collaboration_invalid_message_origin")
                val attempt = CollaborationMessageState.Staging(queue.device, snapshot, origin)
                val saved =
                    checked(workspaceId, owner) {
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
        workspaceId: String,
        owner: CollaborationOwner,
        operation: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val result = operation()
        val admitted = continuation(workspaceId, owner, result)
        return if (admitted is AppResult.Failed) admitted else result
    }

    private suspend fun continuation(
        workspaceId: String,
        owner: CollaborationOwner,
        received: AppResult<*>?,
    ): AppResult<Unit> {
        val rejected = (received as? AppResult.Failed)?.failure
        if (rejected != null && closesCompany(rejected))
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { lists.restrict(owner.localListAccess(), rejected) }
            }
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(owner.localListAccess())
        val current = capture(workspaceId)
        currentCoroutineContext().ensureActive()
        if (rejected != null && closesCompany(rejected)) return AppResult.Failed(rejected)
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        val admitted = requireCollaborationOwner(current, owner, received)
        if (admitted is AppResult.Failed) return admitted
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private fun closesCompany(failure: Failure) =
        failure.kind == FailureKind.Unauthorized ||
            failure.code in workspaceAccessLossCodes ||
            failure.code in
                setOf(
                    "permission_denied",
                    "collaboration_access_denied",
                    "company_module_disabled",
                    "company_maintenance",
                    "client_update_required",
                    "client_version_required",
                )

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)

    private fun failed(code: String, kind: FailureKind = FailureKind.Conflict) =
        collaborationSetupFailure(code, kind)
}
