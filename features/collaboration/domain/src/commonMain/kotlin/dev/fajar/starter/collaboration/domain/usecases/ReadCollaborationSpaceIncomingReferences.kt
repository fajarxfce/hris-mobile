package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

/** One owned pending page; reads no transport and ignores ordinary credential age. */
class ReadCollaborationSpaceIncomingReferences(
    private val history: CollaborationSpaceHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        key: CollaborationHistoryTraversal,
    ): AppResult<CollaborationHistoryReferences> {
        if (
            key.purpose != CollaborationHistoryPurpose.Incoming ||
                !validCollaborationHistoryTraversal(key)
        )
            return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) {
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val loaded = history.progress(access, key)
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            val previous =
                (loaded as AppResult.Success).value
                    ?: return@withTimeoutOrNull AppResult.Success(
                        CollaborationHistoryReferences(null, emptyList(), null)
                    )
            if (
                !validCollaborationHistoryProgress(previous) ||
                    previous.incoming == null ||
                    previous.provider == null
            )
                return@withTimeoutOrNull changed()
            val after = previous.incoming.pendingAfter
            val loadedPage = history.observeReferences(access, key, after, 16).firstOrNull()
            val page =
                (loadedPage as? AppResult.Success)
                    ?.value
                    ?.takeIf { it.events.size <= 16 }
                    ?.let { it.copy(events = it.events.toList()) }
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (loadedPage is AppResult.Failed) return@withTimeoutOrNull loadedPage
            val currentProgress = page?.progress
            if (
                page == null ||
                    currentProgress == null ||
                    currentProgress.generation != previous.generation ||
                    currentProgress.provider != previous.provider ||
                    currentProgress.incoming?.pendingAfter != after
            )
                return@withTimeoutOrNull changed()
            val bounded =
                boundedCollaborationIncomingReferences(page, 16)
                    ?: return@withTimeoutOrNull changed()
            AppResult.Success(bounded)
        } ?: changed(FailureKind.Timeout)
    }

    private suspend fun admission(access: CollaborationSpaceLocalAccess): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = directory.admission(access)
        val current = sessions.current()
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success) {
            if (!validCollaborationSpaceDirectoryAdmission(gate.value)) return changed()
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        }
        if (current is AppResult.Failed) return current
        val owned =
            localCollaborationSpaceAccess(
                access,
                (current as AppResult.Success).value,
                server.audience,
            )
        if (owned is AppResult.Failed) return owned
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_incoming_references_changed", kind)
}
