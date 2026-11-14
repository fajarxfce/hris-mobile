package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Checks empty local change pages; durable cursor advancement belongs to the repair owner. */
class ConfirmCollaborationSpaceHistoryChanges(
    private val history: CollaborationSpaceHistorySnapshotRepository,
    private val companyHistory: CollaborationCompanyHistorySnapshotRepository,
    private val inbox: CollaborationSpaceInboxRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        cursor: CollaborationHistoryChangeCursor?,
        read: CollaborationSpaceHistoryChangesRead,
    ): AppResult<CollaborationSpaceInboxHistoryCommit> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceAccountAccess(
                CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
            ) ||
                !validCollaborationSpaceConversationId(access.spaceId) ||
                !validCollaborationMessageId(conversationId) ||
                !validCollaborationHistoryChanges(read.owner, cursor, 16, read.changes)
        )
            return invalidHistoryConfirmation(FailureKind.Validation)
        val observed =
            when (val changes = read.changes) {
                CollaborationHistoryChanges.Restart ->
                    return invalidHistoryConfirmation(FailureKind.Conflict)
                CollaborationHistoryChanges.Absent -> changes
                is CollaborationHistoryChanges.Page -> {
                    if (changes.eventIds.isNotEmpty())
                        return invalidHistoryConfirmation(FailureKind.Validation)
                    changes.copy(eventIds = emptyList())
                }
            }
        admitted(access).let { if (it is AppResult.Failed) return it }
        val bound = bindings.getByConversation(access, conversationId)
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (bound is AppResult.Failed) return bound
        val selected =
            resolveCollaborationSpaceHistoryLocation(
                access,
                conversationId,
                (bound as AppResult.Success).value,
            )
        if (selected is AppResult.Failed) return selected
        val source = (selected as AppResult.Success).value
        val owner = collaborationHistoryChangeOwner(source)
        if (owner != read.owner) return invalidHistoryConfirmation(FailureKind.Validation)
        val currentIndex = inbox.conversation(access, conversationId, 2)
        val captured =
            when (currentIndex) {
                is AppResult.Failed -> currentIndex
                is AppResult.Success ->
                    captureCollaborationSpaceInboxConversation(conversationId, currentIndex.value)
            }
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (captured is AppResult.Failed) return captured
        val target =
            CollaborationHistoryInboxProposal(
                conversationId,
                (captured as AppResult.Success).value.version,
                null,
            )
        val page = observed as? CollaborationHistoryChanges.Page
        val saved =
            when (source) {
                is CollaborationSpaceHistoryLocation.Space ->
                    history.publishInbox(
                        access,
                        source.conversationId,
                        source.roomId,
                        page?.sourceRevision,
                        target,
                    )
                is CollaborationSpaceHistoryLocation.Original ->
                    companyHistory.publishInbox(access, source, page?.sourceRevision, target)
            }
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (saved is AppResult.Failed) return saved
        val receipt =
            (saved as AppResult.Success).value
                ?: return invalidHistoryConfirmation(FailureKind.Conflict)
        if (!validCollaborationHistoryInboxReceipt(target, page != null, receipt))
            return invalidHistoryConfirmation()
        val version = receipt.source
        if (
            page != null &&
                (version == null ||
                    version.generation != page.window.generation ||
                    version.revision < page.window.throughRevision)
        )
            return invalidHistoryConfirmation()
        return AppResult.Success(
            CollaborationSpaceInboxHistoryCommit(owner, receipt, emptyList(), emptyList())
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
        if (!validCollaborationSpaceDirectoryAdmission(gate)) return invalidHistoryConfirmation()
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

private fun invalidHistoryConfirmation(kind: FailureKind = FailureKind.Unexpected) =
    collaborationRecoveryFailure(
        when (kind) {
            FailureKind.Validation -> "client.collaboration_invalid_request"
            FailureKind.Conflict -> "client.collaboration_inbox_busy"
            else -> "client.collaboration_invalid_response"
        },
        kind,
    )
