package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One explicit raw-generation restart. The owner bounds restarts and schedules continuation. */
class RestartCollaborationSpaceInboxHistorySource(
    private val progress: CollaborationSpaceInboxHistorySourceRepository,
    private val inbox: CollaborationSpaceInboxRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceInboxHistorySourceCheckpoint,
        read: CollaborationSpaceHistoryChangesRead,
    ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceInboxHistorySourceCheckpoint(access, expected) ||
                read.owner != expected.progress.owner ||
                read.changes != CollaborationHistoryChanges.Restart
        )
            return invalidSourceRecovery(FailureKind.Validation)
        if (
            expected.progress.historyCursor == null ||
                expected.progress.historyComplete ||
                expected.version.revision == COLLABORATION_SPACE_INBOX_MAX_ORDER
        )
            return invalidSourceRecovery(FailureKind.Conflict)
        admitted(access).let { if (it is AppResult.Failed) return it }
        val index = inbox.conversation(access, expected.progress.conversationId, 2)
        val captured =
            when (index) {
                is AppResult.Failed -> index
                is AppResult.Success ->
                    captureCollaborationSpaceInboxConversation(
                        expected.progress.conversationId,
                        index.value,
                    )
            }
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (captured is AppResult.Failed) return captured
        val destination = (captured as AppResult.Success).value.version
        if (destination?.generation != expected.progress.destinationGeneration)
            return invalidSourceRecovery(FailureKind.Conflict)
        val replacement =
            expected.progress.copy(
                historyCursor = null,
                historyComplete = false,
                pendingWindow = null,
            )
        val saved =
            progress.mutate(
                access,
                CollaborationSpaceInboxHistorySourceMutation(
                    expected,
                    replacement,
                    destination,
                    emptyList(),
                    emptyList(),
                ),
            )
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (saved is AppResult.Failed) return saved
        val retained =
            (saved as AppResult.Success).value ?: return invalidSourceRecovery(FailureKind.Conflict)
        if (
            !validCollaborationSpaceInboxHistorySourceCheckpoint(access, retained) ||
                retained.progress != replacement ||
                retained.version.generation != expected.version.generation ||
                retained.version.revision != expected.version.revision + 1
        )
            return invalidSourceRecovery()
        return AppResult.Success(retained)
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
        if (!validCollaborationSpaceDirectoryAdmission(gate)) return invalidSourceRecovery()
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

private fun invalidSourceRecovery(kind: FailureKind = FailureKind.Unexpected) =
    collaborationRecoveryFailure(
        when (kind) {
            FailureKind.Validation -> "client.collaboration_invalid_request"
            FailureKind.Conflict -> "client.collaboration_inbox_busy"
            else -> "client.collaboration_invalid_response"
        },
        kind,
    )
