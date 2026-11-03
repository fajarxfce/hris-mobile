package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSetupFailure
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
 * Local names publish first; at most one indexed history row and draft per visible conversation.
 */
class ObserveCollaborationInbox(
    private val lists: CollaborationListRepository,
    private val history: CollaborationHistoryRepository,
    private val drafts: CollaborationDraftRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        selection: Flow<CollaborationInboxSelection>,
    ): Flow<AppResult<CollaborationInboxPage>> =
        sessions
            .observeMessageAccess(workspaceId, workspaces, server, build, clock)
            .flatMapLatest { access ->
                when (access) {
                    is AppResult.Failed -> flowOf(access)
                    is AppResult.Success ->
                        lists.observeAdmission(access.value).flatMapLatest { gate ->
                            when (gate) {
                                is AppResult.Failed -> flowOf(gate)
                                is AppResult.Success ->
                                    gate.value.denial?.let { flowOf(AppResult.Failed(it)) }
                                        ?: selection.distinctUntilChanged().flatMapLatest { selected
                                            ->
                                            if (selected.list.limit !in 1..50) flowOf(invalid())
                                            else
                                                lists
                                                    .observe(access.value, selected.list)
                                                    .flatMapLatest { page ->
                                                        when (page) {
                                                            is AppResult.Failed -> flowOf(page)
                                                            is AppResult.Success ->
                                                                project(
                                                                    workspaceId,
                                                                    access.value,
                                                                    selected,
                                                                    page.value,
                                                                )
                                                        }
                                                    }
                                        }
                            }
                        }
                }
            }
            .buffer(0)
            .distinctUntilChanged()

    private fun project(
        workspaceId: String,
        access: CollaborationLocalAccess,
        selection: CollaborationInboxSelection,
        page: CollaborationLocalList,
    ): Flow<AppResult<CollaborationInboxPage>> = flow {
        val items = page.items.toList()
        if (
            items.size > selection.list.limit ||
                items.map { it.conversation.id }.distinct().size != items.size ||
                items.any { !ownsDraftConversation(access, it.conversation.id, it) }
        ) {
            emit(invalid())
            return@flow
        }
        val guard = continuation(workspaceId, access)
        if (guard is AppResult.Failed) {
            emit(guard)
            return@flow
        }
        val snapshot = page.copy(items = items)
        // Slow/corrupt optional content never blocks navigation through saved conversation names.
        emit(AppResult.Success(CollaborationInboxPage(snapshot)))
        if (!selection.includePreviews || items.isEmpty()) return@flow
        emitAll(
            combine(items.map { preview(access, it.conversation.id) }) { rows ->
                val current = continuation(workspaceId, access)
                if (current is AppResult.Failed) current
                else {
                    val failure =
                        rows.firstNotNullOfOrNull { (it.second as? AppResult.Failed)?.failure }
                    if (failure != null)
                        AppResult.Success(
                            CollaborationInboxPage(snapshot, previewFailure = failure)
                        )
                    else
                        AppResult.Success(
                            CollaborationInboxPage(
                                snapshot,
                                rows.associate {
                                    it.first to (it.second as AppResult.Success).value
                                },
                            )
                        )
                }
            }
        )
    }

    private fun preview(access: CollaborationLocalAccess, conversationId: String) =
        combine(
            history.observe(access, conversationId, limit = 1),
            drafts.observe(access, conversationId),
        ) { messages, draft ->
            val result: AppResult<CollaborationInboxPreview> =
                when {
                    messages is AppResult.Failed -> messages
                    draft is AppResult.Failed -> draft
                    else -> {
                        val page = (messages as AppResult.Success).value
                        val slot = (draft as AppResult.Success).value
                        if (
                            page.messages.size > 1 ||
                                (page.messages.isNotEmpty() && page.roomId == null) ||
                                (slot != null && slot.conversationId != conversationId)
                        )
                            invalid()
                        else
                            AppResult.Success(
                                CollaborationInboxPreview(page.messages.singleOrNull(), slot)
                            )
                    }
                }
            conversationId to result
        }

    private suspend fun continuation(workspaceId: String, access: CollaborationLocalAccess) =
        sessions.checkDraftContinuation(
            workspaceId,
            access,
            lists,
            workspaces,
            server,
            build,
            clock,
        )

    private fun invalid() =
        collaborationSetupFailure("client.collaboration_history_changed", FailureKind.Storage)
}
