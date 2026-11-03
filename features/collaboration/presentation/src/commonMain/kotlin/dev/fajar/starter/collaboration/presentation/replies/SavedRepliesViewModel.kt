package dev.fajar.starter.collaboration.presentation.replies

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class SavedRepliesViewModel(
    @InjectedParam private val workspaceId: String,
    @InjectedParam private val replyId: String,
    private val observe: ObserveCollaborationSavedReplies,
    private val discard: DiscardCollaborationNotificationReply,
) :
    MviViewModel<SavedRepliesState, SavedRepliesEvent, SavedRepliesEffect>(
        SavedRepliesState(replyId)
    ) {
    private var visible = false
    private var observation: Job? = null
    private var work: Job? = null

    init {
        on<SavedRepliesEvent.Entered>(::entered)
        on<SavedRepliesEvent.Stopped>(::stopped)
        on<SavedRepliesEvent.RefreshRequested>(::refresh)
        on<SavedRepliesEvent.BackRequested>(::back)
        on<SavedRepliesEvent.ReplySelected>(::selected)
        on<SavedRepliesEvent.OpenChatRequested>(::openChat)
        on<SavedRepliesEvent.DiscardRequested>(::requestDiscard)
        on<SavedRepliesEvent.DiscardDismissed>(::dismissDiscard)
        on<SavedRepliesEvent.DiscardConfirmed>(::confirmDiscard)
    }

    private fun entered(event: SavedRepliesEvent.Entered) {
        if (visible) return
        visible = true
        restart()
    }

    private fun restart() {
        val previous = observation
        val previousWork = work
        previous?.cancel()
        previousWork?.cancel()
        updateState { SavedRepliesState(replyId) }
        observation =
            viewModelScope.launch {
                previous?.join()
                previousWork?.join()
                ensureActive()
                observe(workspaceId).collect { result ->
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> {
                            // Clear private content before awaiting an obsolete mutation's cleanup.
                            updateState {
                                SavedRepliesState(
                                    replyId,
                                    loading = false,
                                    failure = result.failure,
                                )
                            }
                            work?.cancelAndJoin()
                        }
                        is AppResult.Success -> {
                            val previousAccess = state.value.snapshot?.access
                            if (previousAccess != null && previousAccess != result.value.access) {
                                updateState { SavedRepliesState(replyId) }
                                work?.cancelAndJoin()
                                ensureActive()
                            }
                            updateState {
                                it.copy(
                                    snapshot = result.value,
                                    loading = false,
                                    failure = null,
                                    confirmation =
                                        it.confirmation &&
                                            result.value.items.any { row ->
                                                row.input.id == replyId
                                            },
                                )
                            }
                        }
                    }
                }
            }
    }

    private fun stopped(event: SavedRepliesEvent.Stopped) {
        visible = false
        observation?.cancel()
        work?.cancel()
        updateState { SavedRepliesState(replyId) }
    }

    private fun refresh(event: SavedRepliesEvent.RefreshRequested) {
        if (visible && !state.value.busy) restart()
    }

    private fun back(event: SavedRepliesEvent.BackRequested) {
        if (!visible) return
        leave(SavedRepliesEffect.Back)
    }

    private fun selected(event: SavedRepliesEvent.ReplySelected) {
        val current = state.value
        if (
            !visible ||
                current.loading ||
                current.failure != null ||
                current.busy ||
                current.snapshot?.items?.none { it.input.id == event.id } != false
        )
            return
        leave(SavedRepliesEffect.OpenReply(event.id))
    }

    private fun openChat(event: SavedRepliesEvent.OpenChatRequested) {
        val current = state.value
        val selected = current.selected ?: return
        if (
            !visible ||
                current.loading ||
                current.failure != null ||
                current.busy ||
                selected.conversationName == null
        )
            return
        leave(SavedRepliesEffect.OpenChat(selected.input.candidate.action.target.conversationId))
    }

    private fun leave(effect: SavedRepliesEffect) {
        stopped(SavedRepliesEvent.Stopped)
        viewModelScope.launch { emitEffect(effect) }
    }

    private fun requestDiscard(event: SavedRepliesEvent.DiscardRequested) {
        val current = state.value
        if (
            !visible ||
                current.loading ||
                current.busy ||
                current.failure != null ||
                current.selected == null
        )
            return
        updateState { it.copy(confirmation = true, actionFailure = null, outcome = null) }
    }

    private fun dismissDiscard(event: SavedRepliesEvent.DiscardDismissed) {
        if (!state.value.busy) updateState { it.copy(confirmation = false) }
    }

    private fun confirmDiscard(event: SavedRepliesEvent.DiscardConfirmed) {
        val current = state.value
        val input = current.selected?.input ?: return
        val access = current.snapshot?.access ?: return
        if (
            !visible ||
                !current.confirmation ||
                current.busy ||
                current.loading ||
                current.failure != null
        )
            return
        val previous = work
        updateState { it.copy(busy = true, actionFailure = null, outcome = null) }
        work =
            viewModelScope.launch {
                previous?.join()
                ensureActive()
                val result = discard(workspaceId, access, input)
                ensureActive()
                if (!visible || state.value.snapshot?.access != access) return@launch
                when (result) {
                    is AppResult.Failed ->
                        updateState { it.copy(busy = false, actionFailure = result.failure) }
                    is AppResult.Success ->
                        updateState {
                            it.copy(
                                busy = false,
                                confirmation = false,
                                actionFailure = null,
                                outcome = result.value,
                            )
                        }
                }
            }
    }
}
