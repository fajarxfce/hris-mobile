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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** Local discovery for a task-key-only platform wakeup. It never acquires a native runtime. */
class ReadCollaborationDeliveryWork(
    private val messages: CollaborationMessageRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val cursors: CollaborationDeliveryCursorRepository,
) {
    suspend operator fun invoke(): AppResult<CollaborationDeliveryWork?> {
        val identity = sessions.current()
        currentCoroutineContext().ensureActive()
        if (identity is AppResult.Failed) return identity
        val session = (identity as AppResult.Success).value ?: return AppResult.Success(null)
        val selected = workspaces.current(session.id)
        currentCoroutineContext().ensureActive()
        if (selected is AppResult.Failed) return selected
        val workspace = (selected as AppResult.Success).value ?: return AppResult.Success(null)
        val captured = sessions.captureMessageAccess(workspace.id, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        if (
            access.sessionId != session.id ||
                access.accountId != session.user.id ||
                access.companyId != workspace.company.id
        )
            return changed()
        return read(CollaborationLocalScope.Selected(workspace.id), access)
    }

    suspend operator fun invoke(
        scope: CollaborationLocalScope
    ): AppResult<CollaborationDeliveryWork?> {
        val captured = sessions.captureMessageAccess(scope, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        return read(scope, (captured as AppResult.Success).value)
    }

    private suspend fun read(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
    ): AppResult<CollaborationDeliveryWork?> {
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        val stored =
            when (val received = messages.observe(access, null).first()) {
                is AppResult.Failed -> received
                is AppResult.Success -> {
                    if (received.value.size > 128)
                        return collaborationSetupFailure(
                            "client.collaboration_delivery_changed",
                            FailureKind.Storage,
                        )
                    AppResult.Success(received.value.toList())
                }
            }
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        if (stored is AppResult.Failed) return stored
        val records = (stored as AppResult.Success).value
        if (
            records.size > 128 ||
                records.map { it.intent.id }.distinct().size != records.size ||
                records.any {
                    it.intent.owner != access.messageOwner() ||
                        !validCollaborationMessageId(it.intent.id) ||
                        !validCollaborationMessageId(it.intent.conversationId) ||
                        !validCollaborationMessageState(it.state)
                }
        )
            return collaborationSetupFailure(
                "client.collaboration_delivery_changed",
                FailureKind.Storage,
            )
        if (records.isEmpty()) return AppResult.Success(null)
        val position = cursors.current(access)
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        if (position is AppResult.Failed) return position
        val cursor = (position as AppResult.Success).value
        if (
            cursor != null &&
                (!validCollaborationMessageId(cursor.generation) ||
                    !validCollaborationMessageId(cursor.afterConversationId))
        )
            return collaborationSetupFailure(
                "client.collaboration_delivery_changed",
                FailureKind.Storage,
            )
        val ids = records.map { it.intent.conversationId }.distinct().sorted()
        val first =
            cursor
                ?.afterConversationId
                ?.let { after -> ids.indexOfFirst { it > after } }
                ?.takeIf { it >= 0 } ?: 0
        return AppResult.Success(
            CollaborationDeliveryWork(scope, access, ids.drop(first) + ids.take(first), cursor)
        )
    }

    private suspend fun continuation(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(access)
        val current = sessions.captureMessageAccess(scope, workspaces, server, build, clock)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != access) return changed()
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private fun changed() =
        collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
}
