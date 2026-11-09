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

/** One live-admitted backward page; only an atomic reference commit publishes progress. */
class AcquireCollaborationProviderHistory(
    private val history: CollaborationHistoryTraversalRepository,
    private val lists: CollaborationListRepository,
    private val transport: CollaborationTransportRepository,
    private val runtimes: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        deviceId: String,
        conversationId: String,
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition,
        purpose: CollaborationHistoryPurpose = CollaborationHistoryPurpose.Browse,
    ): AppResult<CollaborationProviderHistoryAcquisition> =
        invoke(
            CollaborationOperationScope.Selected(workspaceId),
            deviceId,
            conversationId,
            origin,
            purpose,
        )

    suspend operator fun invoke(
        scope: CollaborationOperationScope,
        deviceId: String,
        conversationId: String,
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition,
        purpose: CollaborationHistoryPurpose = CollaborationHistoryPurpose.Browse,
    ): AppResult<CollaborationProviderHistoryAcquisition> {
        if (
            !validCollaborationMessageId(deviceId) ||
                !validCollaborationMessageId(conversationId) ||
                !collaborationOperationOwnsDevice(scope, deviceId)
        )
            return failed("client.collaboration_invalid_history", FailureKind.Validation)
        val captured = capture(scope)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        continuation(scope, owner, null).let { if (it is AppResult.Failed) return it }
        val admitted = admitRoom(scope, owner, deviceId, conversationId)
        if (admitted is AppResult.Failed) return admitted
        val roomId = (admitted as AppResult.Success).value
        val traversal = CollaborationHistoryTraversal(conversationId, roomId, deviceId, purpose)
        val loaded = checked(scope, owner) { history.progress(owner.localListAccess(), traversal) }
        if (loaded is AppResult.Failed) return loaded
        val previous = (loaded as AppResult.Success).value
        if (
            previous != null &&
                (!validCollaborationHistoryProgress(previous) ||
                    (purpose == CollaborationHistoryPurpose.Browse && previous.incoming != null))
        )
            return failed("client.collaboration_invalid_history_progress")
        val latest = origin == CollaborationHistoryPageOrigin.Latest
        if (!latest && previous?.exhausted == true)
            return AppResult.Success(CollaborationProviderHistoryAcquisition(0, true))
        val position = if (latest) null else previous?.next
        val until = collaborationHistoryUntilPosition(traversal, previous, origin)
        val received = runtimes.readProviderHistory(owner, deviceId, roomId, position, until)
        val snapshot =
            (received as? AppResult.Success)?.value?.let {
                if (it.events.size > 20) null else it.copy(events = it.events.toList())
            }
        continuation(scope, owner, received).let { if (it is AppResult.Failed) return it }
        if (received is AppResult.Failed) return received
        if (
            snapshot == null ||
                snapshot.owner != owner ||
                snapshot.requestedPosition != position ||
                snapshot.requestedUntilPosition != until ||
                !validCollaborationProviderPage(snapshot, owner.localListAccess(), traversal)
        )
            return failed("client.collaboration_invalid_history_page")
        val readmitted = admitRoom(scope, owner, deviceId, conversationId)
        if (readmitted is AppResult.Failed) return readmitted
        if ((readmitted as AppResult.Success).value != roomId)
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        val staged = history.stage(owner.localListAccess(), traversal, previous, snapshot, origin)
        continuation(scope, owner, staged).let { if (it is AppResult.Failed) return it }
        if (staged is AppResult.Failed) return staged
        if (!(staged as AppResult.Success).value)
            return failed("client.collaboration_history_progress_changed")
        return AppResult.Success(
            CollaborationProviderHistoryAcquisition(snapshot.events.size, snapshot.next == null)
        )
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
