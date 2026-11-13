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

/** Explicit cancellation of one untouched local original, including atomic inbox repair demand. */
class CancelCollaborationSpaceText(
    private val messages: CollaborationSpaceMessageRepository,
    private val cancellations: CollaborationSpaceMessageCancellationRepository,
    private val progress: CollaborationSpaceInboxRepairProgressRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceMessageRecord,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationSpaceMessageCancellation(access, expected))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_message",
                FailureKind.Validation,
            )
        val demand = Uuid.random().toString()
        repeat(3) {
            admitted(access).let { if (it is AppResult.Failed) return it }
            val read = messages.get(access, expected.intent.id)
            admitted(access).let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            val retained = (read as AppResult.Success).value
            if (retained != null && !validCollaborationSpaceMessageRecord(access, retained))
                return collaborationRecoveryFailure(
                    "client.collaboration_invalid_response",
                    FailureKind.Unexpected,
                )
            // Exact cancellation confirmation precedes checkpoint acquisition: replay cannot create
            // fresh repair demand or depend on progress already acknowledged by another owner.
            if (retained == expected.copy(state = CollaborationMessageState.Cancelled))
                return AppResult.Success(Unit)
            if (retained != expected)
                return collaborationRecoveryFailure(
                    "client.collaboration_cancellation_unavailable",
                    FailureKind.Conflict,
                )
            val checkpoint = progress.current(access)
            admitted(access).let { if (it is AppResult.Failed) return it }
            if (checkpoint is AppResult.Failed) return checkpoint
            val current = (checkpoint as AppResult.Success).value
            if (current != null && !validCollaborationSpaceInboxRepairProgress(current))
                return collaborationRecoveryFailure(
                    "client.collaboration_invalid_response",
                    FailureKind.Unexpected,
                )
            val prepared =
                collaborationSpaceInboxRepairProgress(
                    current,
                    CollaborationSpaceInboxRepairProgressChange.Request(demand),
                    Uuid.random().toString(),
                )
            if (prepared is AppResult.Failed) return prepared
            val publication =
                CollaborationSpaceInboxRepairPublication(
                    current,
                    (prepared as AppResult.Success).value,
                )
            currentCoroutineContext().ensureActive()
            val cancelled = cancellations.cancel(access, expected, publication)
            admitted(access).let { if (it is AppResult.Failed) return it }
            if (cancelled is AppResult.Failed) return cancelled
            if ((cancelled as AppResult.Success).value) return AppResult.Success(Unit)
            // False means contention with the exact command or checkpoint. Re-read both; started
            // work becomes a conflict rather than another attempt to cancel native delivery.
        }
        return collaborationRecoveryFailure(
            "client.collaboration_cancellation_unavailable",
            FailureKind.Conflict,
        )
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
