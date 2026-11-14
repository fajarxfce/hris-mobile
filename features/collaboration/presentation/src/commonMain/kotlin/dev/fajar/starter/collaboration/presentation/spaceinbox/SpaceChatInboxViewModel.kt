package dev.fajar.starter.collaboration.presentation.spaceinbox

import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceDirectoryResponseClosesRead
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

/** The retained account/Group route owns one Pager and the visible contact subscriptions. */
@KoinViewModel
class SpaceChatInboxViewModel(
    @InjectedParam private val access: CollaborationSpaceLocalAccess,
    observeVersion: ObserveCollaborationSpaceInboxDestination,
    private val read: ReadCollaborationSpaceInboxWindow,
    private val observeLabels: ObserveCollaborationSpaceInboxLabels,
) :
    MviViewModel<SpaceChatInboxState, SpaceChatInboxEvent, SpaceChatInboxEffect>(
        SpaceChatInboxState()
    ) {
    private val publications = MutableStateFlow(SpaceChatInboxPublication(0, null))
    private val visibleIds = MutableStateFlow<List<String>>(emptyList())
    private var loadedIds: Set<String> = emptySet()

    val entries =
        Pager(
                config =
                    PagingConfig(
                        pageSize = 50,
                        initialLoadSize = 50,
                        prefetchDistance = 10,
                        maxSize = 150,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    SpaceChatInboxPagingSource(viewModelScope, publications) { anchor, count ->
                        read(access, anchor, count)
                    }
                },
            )
            .flow
            .cachedIn(viewModelScope)

    init {
        on<SpaceChatInboxEvent.Entered>(::onEntered)
        on<SpaceChatInboxEvent.Stopped>(::onStopped)
        on<SpaceChatInboxEvent.ViewportChanged>(::onViewportChanged)
        on<SpaceChatInboxEvent.ConversationSelected>(::onConversationSelected)
        viewModelScope.launch {
            observeVersion(access).distinctUntilChanged().collect { result ->
                ensureActive()
                publications.value =
                    SpaceChatInboxPublication(publications.value.sequence + 1, result)
                when (result) {
                    is AppResult.Success -> updateState { it.copy(restriction = null) }
                    is AppResult.Failed ->
                        if (collaborationSpaceDirectoryResponseClosesRead(result.failure))
                            updateState {
                                it.copy(names = emptyMap(), restriction = result.failure)
                            }
                }
            }
        }
        viewModelScope.launch { observeVisibleLabels() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeVisibleLabels() {
        combine(
                visibleIds,
                state.map { it.visible && it.restriction == null }.distinctUntilChanged(),
            ) { ids, visible ->
                if (visible) ids else emptyList()
            }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) flowOf(AppResult.Success(emptyList()))
                else observeLabels(access, ids)
            }
            .collect { result ->
                currentCoroutineContext().ensureActive()
                when (result) {
                    is AppResult.Failed ->
                        if (collaborationSpaceDirectoryResponseClosesRead(result.failure))
                            updateState {
                                it.copy(names = emptyMap(), restriction = result.failure)
                            }
                    is AppResult.Success ->
                        updateState { previous ->
                            if (previous.restriction != null) previous
                            else
                                previous.copy(
                                    names =
                                        previous.names.filterKeys { it in loadedIds } +
                                            result.value
                                                .filter {
                                                    it.conversationId in loadedIds &&
                                                        it.failure == null
                                                }
                                                .associate { it.conversationId to it.displayName }
                                )
                        }
                }
            }
    }

    private fun onEntered(event: SpaceChatInboxEvent.Entered) {
        updateState { it.copy(visible = true) }
    }

    private fun onStopped(event: SpaceChatInboxEvent.Stopped) {
        updateState { it.copy(visible = false) }
    }

    private fun onViewportChanged(event: SpaceChatInboxEvent.ViewportChanged) {
        loadedIds = (event.visible + event.loaded).distinct().take(200).toSet()
        visibleIds.value = event.visible.distinct().filter { it in loadedIds }.take(50)
        updateState { it.copy(names = it.names.filterKeys { id -> id in loadedIds }) }
    }

    private fun onConversationSelected(event: SpaceChatInboxEvent.ConversationSelected) {
        if (!state.value.visible || state.value.restriction != null || event.id !in loadedIds)
            return
        updateState { it.copy(visible = false) }
        viewModelScope.launch { emitEffect(SpaceChatInboxEffect.OpenConversation(event.id)) }
    }
}
