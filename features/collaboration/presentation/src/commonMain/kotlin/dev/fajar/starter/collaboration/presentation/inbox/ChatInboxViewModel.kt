package dev.fajar.starter.collaboration.presentation.inbox

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.CollaborationListCheckpoint
import dev.fajar.starter.collaboration.domain.entities.CollaborationListSelection
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationList
import dev.fajar.starter.collaboration.domain.usecases.RefreshCollaborationList
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ChatInboxViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveCollaborationList,
    private val refresh: RefreshCollaborationList,
) : MviViewModel<ChatInboxState, ChatInboxEvent, ChatInboxEffect>(ChatInboxState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null
    private var visible = false
    private var refreshRequired = true
    private var advanceAfterAcquisition = false
    private var advanceFrom: CollaborationListCheckpoint? = null

    init {
        on<ChatInboxEvent.Entered>(::onEntered)
        on<ChatInboxEvent.Stopped>(::onStopped)
        on<ChatInboxEvent.RefreshRequested>(::onRefreshRequested)
        on<ChatInboxEvent.NextRequested>(::onNextRequested)
        on<ChatInboxEvent.PreviousRequested>(::onPreviousRequested)
        on<ChatInboxEvent.SavedRepliesRequested>(::onSavedRepliesRequested)
        on<ChatInboxEvent.NewChatRequested>(::onNewChatRequested)
        on<ChatInboxEvent.BackRequested>(::onBackRequested)
        on<ChatInboxEvent.ConversationSelected>(::onConversationSelected)
    }

    private fun onEntered(event: ChatInboxEvent.Entered) {
        if (visible) return
        visible = true
        if (observation?.isActive != true) startObservation()
        if (refreshRequired) acquire(null)
    }

    private fun startObservation() {
        observation?.cancel()
        observation =
            viewModelScope.launch {
                observe(
                        workspaceId,
                        state.map { CollaborationListSelection(it.after) }.distinctUntilChanged(),
                    )
                    .collect { result ->
                        ensureActive()
                        when (result) {
                            is AppResult.Failed -> {
                                advanceAfterAcquisition = false
                                updateState {
                                    ChatInboxState(
                                        loading = false,
                                        failure = result.failure,
                                        refreshing = it.refreshing,
                                    )
                                }
                            }
                            is AppResult.Success -> {
                                updateState {
                                    it.copy(page = result.value, loading = false, failure = null)
                                }
                                if (
                                    visible &&
                                        !state.value.refreshing &&
                                        advanceAfterAcquisition &&
                                        result.value.checkpoint != advanceFrom
                                ) {
                                    advanceAfterAcquisition = false
                                    result.value.nextCursor?.let(::nextPage)
                                }
                            }
                        }
                    }
            }
    }

    private fun onStopped(event: ChatInboxEvent.Stopped) {
        visible = false
        acquisition?.cancel()
        acquisition = null
        advanceAfterAcquisition = false
        // Keep the owned local observer so a hidden destination clears on scope/denial changes.
        updateState { it.copy(loading = false, refreshing = false) }
    }

    private fun onRefreshRequested(event: ChatInboxEvent.RefreshRequested) {
        if (!visible || acquisition?.isActive == true) return
        advanceAfterAcquisition = false
        updateState {
            if (it.after == null) it.copy(previous = emptyList())
            else it.copy(after = null, previous = emptyList(), page = null, loading = true)
        }
        acquire(null)
    }

    private fun acquire(after: String?) {
        if (!visible || acquisition?.isActive == true) return
        refreshRequired = true
        updateState { it.copy(refreshing = true, refreshFailure = null) }
        acquisition =
            viewModelScope.launch {
                val result = refresh(workspaceId, after)
                ensureActive()
                when (result) {
                    is AppResult.Failed -> {
                        advanceAfterAcquisition = false
                        updateState { it.copy(refreshing = false, refreshFailure = result.failure) }
                    }
                    is AppResult.Success -> {
                        refreshRequired = false
                        startObservation()
                        updateState {
                            it.copy(
                                refreshing = false,
                                remoteNext = result.value.value.nextCursor,
                                refreshFailure = null,
                            )
                        }
                        if (
                            advanceAfterAcquisition && state.value.page?.checkpoint != advanceFrom
                        ) {
                            advanceAfterAcquisition = false
                            state.value.page?.nextCursor?.let(::nextPage)
                        }
                    }
                }
            }
    }

    private fun onNextRequested(event: ChatInboxEvent.NextRequested) {
        if (!visible || state.value.restricted || state.value.loading || state.value.refreshing)
            return
        val page = state.value.page ?: return
        val cursor = page.nextCursor
        if (cursor != null) nextPage(cursor)
        else
            state.value.remoteNext?.let {
                advanceFrom = page.checkpoint
                advanceAfterAcquisition = true
                acquire(it)
            }
    }

    private fun nextPage(after: String) {
        if (state.value.previous.size >= 200) return
        updateState {
            it.copy(
                after = after,
                previous = it.previous + listOf(it.after),
                page = null,
                loading = true,
            )
        }
        viewModelScope.launch { emitEffect(ChatInboxEffect.ScrollToStart) }
    }

    private fun onPreviousRequested(event: ChatInboxEvent.PreviousRequested) {
        val previous = state.value.previous
        if (
            !visible ||
                state.value.restricted ||
                state.value.loading ||
                state.value.refreshing ||
                previous.isEmpty()
        )
            return
        advanceAfterAcquisition = false
        updateState {
            it.copy(
                after = previous.last(),
                previous = previous.dropLast(1),
                page = null,
                loading = true,
            )
        }
        viewModelScope.launch { emitEffect(ChatInboxEffect.ScrollToStart) }
    }

    private fun onConversationSelected(event: ChatInboxEvent.ConversationSelected) {
        if (
            !visible ||
                state.value.restricted ||
                state.value.page?.items?.none { it.conversation.id == event.id } != false
        )
            return
        onStopped(ChatInboxEvent.Stopped)
        viewModelScope.launch { emitEffect(ChatInboxEffect.OpenConversation(event.id)) }
    }

    private fun onSavedRepliesRequested(event: ChatInboxEvent.SavedRepliesRequested) {
        if (!visible || state.value.restricted) return
        onStopped(ChatInboxEvent.Stopped)
        viewModelScope.launch { emitEffect(ChatInboxEffect.SavedReplies) }
    }

    private fun onNewChatRequested(event: ChatInboxEvent.NewChatRequested) {
        if (!visible || state.value.restricted) return
        onStopped(ChatInboxEvent.Stopped)
        viewModelScope.launch { emitEffect(ChatInboxEffect.NewChat) }
    }

    private fun onBackRequested(event: ChatInboxEvent.BackRequested) {
        if (!visible) return
        onStopped(ChatInboxEvent.Stopped)
        viewModelScope.launch { emitEffect(ChatInboxEffect.Back) }
    }
}
