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

/** One explicitly requested, live-admitted original window. No cursor advancement or retry. */
class AcquireCollaborationHistory(
    private val history: CollaborationHistoryRepository,
    private val lists: CollaborationListRepository,
    private val transport: CollaborationTransportRepository,
    private val runtimes: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val edits: CollaborationMessageEditRepository,
) {
    suspend operator fun invoke(
        workspaceId: String,
        deviceId: String,
        conversationId: String,
        anchorEventId: String? = null,
    ): AppResult<CollaborationHistoryAcquisition> =
        invoke(
            CollaborationOperationScope.Selected(workspaceId),
            deviceId,
            conversationId,
            anchorEventId,
        )

    suspend operator fun invoke(
        scope: CollaborationOperationScope,
        deviceId: String,
        conversationId: String,
        anchorEventId: String? = null,
    ): AppResult<CollaborationHistoryAcquisition> {
        if (
            !validCollaborationMessageId(deviceId) ||
                !collaborationOperationOwnsDevice(scope, deviceId) ||
                !validCollaborationMessageId(conversationId) ||
                (anchorEventId != null && !validCollaborationEventId(anchorEventId))
        )
            return failed("client.collaboration_invalid_history", FailureKind.Validation)
        val captured = capture(scope)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        continuation(scope, owner, null).let { if (it is AppResult.Failed) return it }
        val admitted = admitRoom(scope, owner, deviceId, conversationId)
        if (admitted is AppResult.Failed) return admitted
        val roomId = (admitted as AppResult.Success).value
        val anchor =
            if (anchorEventId != null) anchorEventId
            else {
                val found = runtimes.findHistoryHead(owner, deviceId, roomId)
                continuation(scope, owner, found).let { if (it is AppResult.Failed) return it }
                if (found is AppResult.Failed) return found
                val head = (found as AppResult.Success).value
                if (
                    head.owner != owner ||
                        head.deviceId != deviceId ||
                        head.roomId != roomId ||
                        (head.eventId != null && !validCollaborationEventId(head.eventId))
                )
                    return failed("client.collaboration_invalid_history_head")
                if (head.eventId == null) {
                    val emptyAdmission = admitRoom(scope, owner, deviceId, conversationId)
                    if (emptyAdmission is AppResult.Failed) return emptyAdmission
                    if ((emptyAdmission as AppResult.Success).value != roomId)
                        return failed(
                            "client.collaboration_conversation_mismatch",
                            FailureKind.AccessDenied,
                        )
                    return AppResult.Success(CollaborationHistoryAcquisition(0, 0))
                }
                head.eventId
            }
        val received = runtimes.readOriginalHistory(owner, deviceId, roomId, anchor)
        // Detach bounded input before another repository can suspend or mutate its source.
        val snapshot =
            (received as? AppResult.Success)?.value?.let {
                if (
                    it.changes.size > 128 || it.unresolvedEventIds.size > 128 || it.edits.size > 128
                )
                    null
                else
                    it.copy(
                        changes = it.changes.toList(),
                        unresolvedEventIds = it.unresolvedEventIds.toList(),
                        edits = it.edits.toList(),
                    )
            }
        continuation(scope, owner, received).let { if (it is AppResult.Failed) return it }
        if (received is AppResult.Failed) return received
        if (
            snapshot == null ||
                !validCollaborationOriginalWindow(snapshot, owner, deviceId, roomId, anchor)
        )
            return failed("client.collaboration_invalid_history_window")
        val readmitted = admitRoom(scope, owner, deviceId, conversationId)
        if (readmitted is AppResult.Failed) return readmitted
        if ((readmitted as AppResult.Success).value != roomId)
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        val retained = persistWindow(scope, owner, conversationId, roomId, snapshot)
        if (retained is AppResult.Failed) return retained
        return AppResult.Success(
            CollaborationHistoryAcquisition(snapshot.changes.size, snapshot.unresolvedEventIds.size)
        )
    }

    private suspend fun persistWindow(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        conversationId: String,
        roomId: String,
        snapshot: CollaborationOriginalWindow,
    ): AppResult<Unit> {
        for (batch in snapshot.changes.chunked(16)) {
            val merged = history.merge(owner.localListAccess(), conversationId, roomId, batch)
            continuation(scope, owner, merged).let { if (it is AppResult.Failed) return it }
            if (merged is AppResult.Failed) return merged
            if (!(merged as AppResult.Success).value)
                return failed("client.collaboration_history_changed")
        }
        for (batch in snapshot.edits.chunked(16)) {
            val merged = edits.mergeEdits(owner.localListAccess(), conversationId, roomId, batch)
            continuation(scope, owner, merged).let { if (it is AppResult.Failed) return it }
            if (merged is AppResult.Failed) return merged
            if (!(merged as AppResult.Success).value)
                return failed("client.collaboration_history_changed")
        }
        return AppResult.Success(Unit)
    }

    private suspend fun admitRoom(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        deviceId: String,
        conversationId: String,
    ): AppResult<String> {
        val read = checked(scope, owner) { transport.read(owner, conversationId) }
        if (read is AppResult.Failed) return read
        return collaborationTransportMetadataRoom(
            (read as AppResult.Success).value,
            owner,
            deviceId,
            conversationId,
            build.platform.name.uppercase(),
        )
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
