package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One admitted local window. A removed refresh anchor permits exactly one latest-page fallback. */
class ReadCollaborationSpaceInboxWindow(
    private val windows: CollaborationSpaceInboxWindowRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        anchor: CollaborationSpaceInboxWindowAnchor = CollaborationSpaceInboxWindowAnchor.Latest,
        limit: Int = 50,
    ): AppResult<CollaborationSpaceInboxWindow> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationSpaceInboxWindowRequest(access, anchor, limit))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        val before = admitted(access)
        if (before is AppResult.Failed) return before
        val first = readWindow(access, anchor, limit)
        return if (
            first is AppResult.Success && first.value == CollaborationSpaceInboxWindow.MissingAnchor
        )
            readWindow(access, CollaborationSpaceInboxWindowAnchor.Latest, limit)
        else first
    }

    private suspend fun readWindow(
        access: CollaborationSpaceLocalAccess,
        anchor: CollaborationSpaceInboxWindowAnchor,
        limit: Int,
    ): AppResult<CollaborationSpaceInboxWindow> {
        currentCoroutineContext().ensureActive()
        val read = windows.read(access, anchor, limit)
        currentCoroutineContext().ensureActive()
        // Bound and detach repository lists before the current-admission reads can suspend.
        val snapshot =
            when (read) {
                is AppResult.Failed -> read
                is AppResult.Success -> {
                    val value = read.value
                    if (value is CollaborationSpaceInboxWindow.Items && value.entries.size > limit)
                        collaborationRecoveryFailure(
                            "client.collaboration_invalid_response",
                            FailureKind.Unexpected,
                        )
                    else
                        AppResult.Success(
                            if (value is CollaborationSpaceInboxWindow.Items)
                                value.copy(entries = value.entries.toList())
                            else value
                        )
                }
            }
        val current = admitted(access)
        if (current is AppResult.Failed) return current
        if (snapshot is AppResult.Failed) return snapshot
        val value = (snapshot as AppResult.Success).value
        return if (validCollaborationSpaceInboxWindow(access, anchor, limit, value)) snapshot
        else
            collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
    }

    private suspend fun admitted(access: CollaborationSpaceLocalAccess): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val identity = sessions.current()
        currentCoroutineContext().ensureActive()
        if (identity is AppResult.Failed) return identity
        val owner =
            localCollaborationSpaceAccess(
                access,
                (identity as AppResult.Success).value,
                server.audience,
            )
        if (owner is AppResult.Failed) return owner
        val read = directory.admission(access)
        currentCoroutineContext().ensureActive()
        if (read is AppResult.Failed) return read
        val gate = (read as AppResult.Success).value
        if (!validCollaborationSpaceDirectoryAdmission(gate))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
        gate.denial?.let {
            return AppResult.Failed(it)
        }
        val latest = sessions.current()
        currentCoroutineContext().ensureActive()
        if (latest is AppResult.Failed) return latest
        val current =
            localCollaborationSpaceAccess(
                access,
                (latest as AppResult.Success).value,
                server.audience,
            )
        return if (current is AppResult.Failed) current else AppResult.Success(Unit)
    }
}
