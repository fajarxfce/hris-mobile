package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One admitted local page. The existing repair owner controls continuation and publication. */
class ReadCollaborationSpaceHistoryChanges(
    private val history: CollaborationSpaceHistoryChangesRepository,
    private val companyHistory: CollaborationCompanyHistoryChangesRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        cursor: CollaborationHistoryChangeCursor? = null,
        limit: Int = 16,
    ): AppResult<CollaborationSpaceHistoryChangesRead> {
        currentCoroutineContext().ensureActive()
        if (
            (!validCollaborationSpaceAccountAccess(
                CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
            ) || !validCollaborationSpaceConversationId(access.spaceId)) ||
                !validCollaborationMessageId(conversationId) ||
                limit !in 1..16 ||
                !validCollaborationHistoryChangeCursor(cursor)
        )
            return invalidHistoryChanges(FailureKind.Validation)
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
        if (cursor != null && collaborationHistoryChangeCursorOwner(cursor) != owner)
            return invalidHistoryChanges(FailureKind.Validation)
        val read =
            when (source) {
                is CollaborationSpaceHistoryLocation.Space -> history.changes(source, cursor, limit)
                is CollaborationSpaceHistoryLocation.Original ->
                    companyHistory.changes(source, cursor, limit)
            }
        // Freeze repository-owned collections before the following admission calls can suspend.
        val captured =
            when (read) {
                is AppResult.Failed -> read
                is AppResult.Success ->
                    when (val value = read.value) {
                        is CollaborationHistoryChanges.Page ->
                            if (value.eventIds.size > limit) invalidHistoryChanges()
                            else AppResult.Success(value.copy(eventIds = value.eventIds.toList()))
                        else -> read
                    }
            }
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (captured is AppResult.Failed) return captured
        val changes = (captured as AppResult.Success).value
        if (!validCollaborationHistoryChanges(owner, cursor, limit, changes))
            return invalidHistoryChanges()
        return AppResult.Success(CollaborationSpaceHistoryChangesRead(owner, changes))
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
        if (!validCollaborationSpaceDirectoryAdmission(gate)) return invalidHistoryChanges()
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

private fun invalidHistoryChanges(kind: FailureKind = FailureKind.Unexpected) =
    collaborationRecoveryFailure(
        if (kind == FailureKind.Validation) "client.collaboration_invalid_request"
        else "client.collaboration_invalid_response",
        kind,
    )
