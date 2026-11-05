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
                                            else observePage(workspaceId, access.value, selected)
                                        }
                            }
                        }
                }
            }
            .buffer(0)
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePage(
        workspaceId: String,
        access: CollaborationLocalAccess,
        selection: CollaborationInboxSelection,
    ): Flow<AppResult<CollaborationInboxPage>> = flow {
        coroutineScope {
            // Both projections share one owned local subscription. Cursor/checkpoint changes
            // update the page without reopening history and draft streams for unchanged IDs.
            val pages =
                lists
                    .observe(access, selection.list)
                    .map { result ->
                        when (result) {
                            is AppResult.Failed -> result
                            is AppResult.Success -> {
                                val items = result.value.items.toList()
                                if (
                                    items.size > selection.list.limit ||
                                        items.map { it.conversation.id }.distinct().size !=
                                            items.size ||
                                        items.any {
                                            !ownsDraftConversation(access, it.conversation.id, it)
                                        }
                                )
                                    invalid()
                                else AppResult.Success(result.value.copy(items = items))
                            }
                        }
                    }
                    .distinctUntilChanged()
                    .shareIn(this, SharingStarted.Lazily, replay = 1)
            val details =
                pages
                    .map { result ->
                        if (selection.includePreviews)
                            (result as? AppResult.Success)?.value?.items.orEmpty().map {
                                it.conversation.id
                            }
                        else emptyList()
                    }
                    .distinctUntilChanged()
                    .flatMapLatest { ids ->
                        val pending = InboxDetails(ids)
                        if (ids.isEmpty()) flowOf(pending)
                        else
                            combine(ids.map { preview(access, it) }) { rows ->
                                    val failure =
                                        rows.firstNotNullOfOrNull {
                                            (it.second as? AppResult.Failed)?.failure
                                        }
                                    if (failure != null) InboxDetails(ids, failure = failure)
                                    else
                                        InboxDetails(
                                            ids,
                                            rows.associate {
                                                it.first to (it.second as AppResult.Success).value
                                            },
                                            loaded = true,
                                        )
                                }
                                .onStart { emit(pending) }
                    }
            emitAll(
                combine(pages, details) { result, detail ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> {
                            val guard = continuation(workspaceId, access)
                            if (guard is AppResult.Failed) guard
                            else {
                                val page = result.value
                                val ids = page.items.map { it.conversation.id }
                                val current = selection.includePreviews && detail.ids == ids
                                AppResult.Success(
                                    CollaborationInboxPage(
                                        page,
                                        access,
                                        if (current) detail.previews else emptyMap(),
                                        previewsLoaded = current && detail.loaded,
                                        previewFailure = detail.failure.takeIf { current },
                                    )
                                )
                            }
                        }
                    }
                }
            )
        }
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

private data class InboxDetails(
    val ids: List<String>,
    val previews: Map<String, CollaborationInboxPreview> = emptyMap(),
    val loaded: Boolean = false,
    val failure: Failure? = null,
)
