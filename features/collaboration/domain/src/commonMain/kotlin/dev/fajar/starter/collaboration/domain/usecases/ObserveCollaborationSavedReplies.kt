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
import kotlinx.coroutines.flow.*

/**
 * All owned saved replies in the selected company, including held inputs after reauthentication.
 */
class ObserveCollaborationSavedReplies(
    private val inputs: CollaborationNotificationReplyRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(workspaceId: String): Flow<AppResult<CollaborationSavedReplies>> =
        sessions
            .observe()
            .onCompletion { cause -> if (cause == null) emit(failed(FailureKind.Unavailable)) }
            .flatMapLatest { identity ->
                when (identity) {
                    is AppResult.Failed -> flowOf(identity)
                    is AppResult.Success -> {
                        val session =
                            identity.value
                                ?: return@flatMapLatest flowOf(failed(FailureKind.Unauthorized))
                        val captured = collaborationPushParent(session, server, build)
                        if (captured is AppResult.Failed) return@flatMapLatest flowOf(captured)
                        val parent = (captured as AppResult.Success).value
                        workspaces
                            .observe(session.id)
                            .onCompletion { cause ->
                                if (cause == null) emit(failed(FailureKind.Unavailable))
                            }
                            .map { workspace ->
                                when (workspace) {
                                    is AppResult.Failed -> workspace
                                    is AppResult.Success ->
                                        collaborationLocalAccess(
                                            workspaceId,
                                            workspace.value,
                                            session,
                                            server.audience,
                                            build,
                                            clock.now(),
                                        )
                                }
                            }
                            .distinctUntilChanged()
                            .flatMapLatest { owned ->
                                when (owned) {
                                    is AppResult.Failed -> flowOf(owned)
                                    is AppResult.Success ->
                                        observe(workspaceId, owned.value, parent)
                                }
                            }
                    }
                }
            }
            .onCompletion { cause -> if (cause == null) emit(failed(FailureKind.Unavailable)) }
            .catch { cause ->
                if (cause is CancellationException) throw cause
                emit(failed(FailureKind.Unexpected))
            }
            .buffer(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe(
        workspaceId: String,
        access: CollaborationLocalAccess,
        parent: CollaborationNativeParent,
    ): Flow<AppResult<CollaborationSavedReplies>> =
        lists
            .observeAdmission(access)
            .onCompletion { cause -> if (cause == null) emit(failed(FailureKind.Unavailable)) }
            .distinctUntilChanged()
            .flatMapLatest { admission ->
                when (admission) {
                    is AppResult.Failed -> flowOf(admission)
                    is AppResult.Success ->
                        admission.value.denial?.let { flowOf(AppResult.Failed(it)) }
                            ?: inputs
                                .observeChanges(
                                    CollaborationRecoveryAccess(
                                        access.sessionId,
                                        access.audience,
                                        access.accountId,
                                    )
                                )
                                .onCompletion { cause ->
                                    if (cause == null) emit(failed(FailureKind.Unavailable))
                                }
                                .mapLatest { change ->
                                    if (change is AppResult.Failed) change
                                    else
                                        withTimeoutOrNull(8_000) {
                                            read(workspaceId, access, parent)
                                        } ?: failed(FailureKind.Timeout)
                                }
                }
            }

    private suspend fun read(
        workspaceId: String,
        access: CollaborationLocalAccess,
        parent: CollaborationNativeParent,
    ): AppResult<CollaborationSavedReplies> {
        suspend fun guard(): AppResult<Unit> {
            val current =
                sessions.checkReadStateContinuation(
                    CollaborationLocalScope.Selected(workspaceId),
                    access,
                    lists,
                    workspaces,
                    server,
                    build,
                    clock,
                )
            if (current is AppResult.Failed) return current
            return sessions.guardPushParent(server, build, parent)
        }
        guard().let { if (it is AppResult.Failed) return it }
        val recovery =
            CollaborationRecoveryAccess(access.sessionId, access.audience, access.accountId)
        val all = mutableListOf<CollaborationNotificationReplyInput>()
        var after: Long? = null
        for (pageIndex in 0 until 4) {
            val result = inputs.page(recovery, after, 20, pendingOnly = false)
            guard().let { if (it is AppResult.Failed) return it }
            if (result is AppResult.Failed) return result
            val page = (result as AppResult.Success).value
            if (
                page.inputs.size > 20 ||
                    page.inputs.any {
                        !validCollaborationNotificationReplyInput(it) ||
                            (after != null && it.sequence <= after) ||
                            it.candidate.parent.audience != access.audience ||
                            it.candidate.parent.accountId != access.accountId
                    } ||
                    page.inputs.zipWithNext().any { (a, b) -> a.sequence >= b.sequence }
            )
                return failed(FailureKind.Conflict)
            all.addAll(page.inputs)
            if (all.size > 64 || all.map { it.id }.distinct().size != all.size)
                return failed(FailureKind.Conflict)
            if (page.nextAfter == null) break
            if (
                page.inputs.isEmpty() ||
                    page.nextAfter != page.inputs.last().sequence ||
                    pageIndex == 3
            )
                return failed(FailureKind.Conflict)
            after = page.nextAfter
        }
        val selected = all.filter { it.candidate.action.target.companyId == access.companyId }
        val names = mutableMapOf<String, String?>()
        for (id in selected.map { it.candidate.action.target.conversationId }.distinct()) {
            val result = lists.get(access, id)
            guard().let { if (it is AppResult.Failed) return it }
            if (result is AppResult.Failed) return result
            val value = (result as AppResult.Success).value
            names[id] =
                if (ownsCollaborationReadConversation(access, id, value)) value?.peer?.displayName
                else null
        }
        guard().let { if (it is AppResult.Failed) return it }
        return AppResult.Success(
            CollaborationSavedReplies(
                access,
                selected.map {
                    CollaborationSavedReply(it, names[it.candidate.action.target.conversationId])
                },
                parent,
            )
        )
    }

    private fun failed(kind: FailureKind) =
        collaborationSetupFailure("client.collaboration_saved_replies_unavailable", kind)
}
