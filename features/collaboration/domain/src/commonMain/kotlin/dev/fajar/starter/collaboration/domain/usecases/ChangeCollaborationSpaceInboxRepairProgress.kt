@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One retained demand/page transition, with at most three genuine compare-and-set attempts. */
class ChangeCollaborationSpaceInboxRepairProgress(
    private val progress: CollaborationSpaceInboxRepairProgressRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        change: CollaborationSpaceInboxRepairProgressChange,
    ): AppResult<CollaborationSpaceInboxRepairProgress> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceInboxRepairRequest(access, null, 1) ||
                !validCollaborationSpaceInboxRepairProgressChange(change)
        )
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        repeat(3) {
            val before = admitted(access)
            if (before is AppResult.Failed) return before
            val read = progress.current(access)
            val afterRead = admitted(access)
            if (afterRead is AppResult.Failed) return afterRead
            if (read is AppResult.Failed) return read
            val current = (read as AppResult.Success).value
            if (current != null && !validCollaborationSpaceInboxRepairProgress(current))
                return collaborationRecoveryFailure(
                    "client.collaboration_invalid_response",
                    FailureKind.Unexpected,
                )
            val prepared =
                collaborationSpaceInboxRepairProgress(current, change, Uuid.random().toString())
            if (prepared is AppResult.Failed) return prepared
            val next = (prepared as AppResult.Success).value
            if (next == current) return prepared
            currentCoroutineContext().ensureActive()
            val saved = progress.replace(access, current, next)
            val afterWrite = admitted(access)
            if (afterWrite is AppResult.Failed) return afterWrite
            if (saved is AppResult.Failed) return saved
            val value = (saved as AppResult.Success).value ?: return@repeat
            if (value != next)
                return collaborationRecoveryFailure(
                    "client.collaboration_invalid_response",
                    FailureKind.Unexpected,
                )
            return AppResult.Success(value)
        }
        return collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Conflict)
    }

    private suspend fun admitted(access: CollaborationSpaceLocalAccess): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val identity = sessions.current()
        currentCoroutineContext().ensureActive()
        if (identity is AppResult.Failed) return identity
        val owned =
            localCollaborationSpaceAccess(
                access,
                (identity as AppResult.Success).value,
                server.audience,
            )
        if (owned is AppResult.Failed) return owned
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
