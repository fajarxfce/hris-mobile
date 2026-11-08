package dev.fajar.starter.collaboration.presentation.inbox

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationContentResponseClosesRead
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationComposerProgress
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationInbox
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSavedReplies
import dev.fajar.starter.collaboration.domain.usecases.SyncCollaborationList
import dev.fajar.starter.collaboration.presentation.replies.needsAttention
import dev.fajar.starter.collaboration.presentation.thread.chatCanReconnect
import dev.fajar.starter.collaboration.presentation.thread.chatReconnectDelay
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ChatInboxViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveCollaborationInbox,
    private val synchronize: SyncCollaborationList,
    private val observeReplies: ObserveCollaborationSavedReplies,
    private val observeComposer: ObserveCollaborationComposerProgress,
) : MviViewModel<ChatInboxState, ChatInboxEvent, ChatInboxEffect>(ChatInboxState()) {
    private var composerObservation: Job? = null
    private var composerOwner: CollaborationLocalAccess? = null
    private val composerOriginals = MutableStateFlow(emptyList<CollaborationComposerSubmission>())
    private var observation: Job? = null
    private var replies: Job? = null
    private var acquisition: Job? = null
    private var reconnection: Job? = null
    private var reconnectAttempt = 0
    private var visible = false
    private val previewsVisible = MutableStateFlow(false)

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
        reconnectAttempt = 0
        previewsVisible.value = true
        if (observation?.isActive != true) startObservation()
        if (replies?.isActive != true) observeUnsentReplies()
        if (composerObservation?.isActive != true) observeComposerPreviews()
        acquire()
    }

    private fun observeComposerPreviews() {
        composerObservation =
            viewModelScope.launch {
                state
                    .map { it.owner }
                    .distinctUntilChanged()
                    .collectLatest { owned ->
                        if (composerOwner != owned) {
                            composerOwner = owned
                            composerOriginals.value = emptyList()
                        }
                        if (owned == null) return@collectLatest
                        combine(
                                observeComposer(workspaceId, owned, composerOriginals),
                                state
                                    .map { value ->
                                        value.page
                                            ?.items
                                            .orEmpty()
                                            .map { it.conversation.id }
                                            .toSet() to value.previews
                                    }
                                    .distinctUntilChanged(),
                            ) { progress, current ->
                                when (progress) {
                                    is AppResult.Failed -> progress
                                    is AppResult.Success ->
                                        AppResult.Success(
                                            chatInboxComposerProjection(
                                                current.first,
                                                current.second,
                                                progress.value,
                                            )
                                        )
                                }
                            }
                            .collect { result ->
                                ensureActive()
                                if (state.value.owner != owned) return@collect
                                when (result) {
                                    is AppResult.Failed -> {
                                        if (collaborationContentResponseClosesRead(result.failure))
                                            updateState {
                                                ChatInboxState(
                                                    loading = false,
                                                    failure = result.failure,
                                                )
                                            }
                                        else
                                            updateState {
                                                it.copy(composerFailure = result.failure)
                                            }
                                    }
                                    is AppResult.Success -> {
                                        updateState {
                                            it.copy(
                                                composerPreviews = result.value.previews,
                                                composerFailure = null,
                                            )
                                        }
                                        composerOriginals.value = result.value.originals
                                    }
                                }
                            }
                    }
            }
    }

    private fun observeUnsentReplies() {
        replies =
            viewModelScope.launch {
                observeReplies(workspaceId).collect { result ->
                    ensureActive()
                    val items =
                        (result as? AppResult.Success)?.value?.items.orEmpty().filter {
                            it.needsAttention
                        }
                    updateState {
                        it.copy(
                            unsentCounts =
                                items
                                    .groupingBy { row ->
                                        row.input.candidate.action.target.conversationId
                                    }
                                    .eachCount(),
                            orphanedUnsentCount =
                                items.count { row -> row.conversationName == null },
                        )
                    }
                }
            }
    }

    private fun startObservation() {
        observation?.cancel()
        observation =
            viewModelScope.launch {
                observe(
                        workspaceId,
                        combine(
                            state
                                .map { CollaborationListSelection(it.after) }
                                .distinctUntilChanged(),
                            previewsVisible,
                        ) { list, details ->
                            CollaborationInboxSelection(list, details)
                        },
                    )
                    .collect { result ->
                        ensureActive()
                        when (result) {
                            is AppResult.Failed -> {
                                updateState {
                                    ChatInboxState(
                                        loading = false,
                                        failure = result.failure,
                                        refreshing = it.refreshing,
                                    )
                                }
                            }
                            is AppResult.Success -> {
                                updateState { it.withPage(result.value) }
                            }
                        }
                    }
            }
    }

    private fun onStopped(event: ChatInboxEvent.Stopped) {
        visible = false
        previewsVisible.value = false
        composerObservation?.cancel()
        composerObservation = null
        reconnection?.cancel()
        reconnection = null
        acquisition?.cancel()
        acquisition = null
        // Keep the owned local observer so a hidden destination clears on scope/denial changes.
        updateState { it.copy(loading = false, refreshing = false) }
    }

    private fun onRefreshRequested(event: ChatInboxEvent.RefreshRequested) {
        if (!visible || acquisition?.isActive == true) return
        updateState {
            if (it.after == null) it.copy(previous = emptyList())
            else it.copy(after = null, previous = emptyList(), page = null, loading = true)
        }
        // Retry a completed local content read even when remote refresh remains unavailable.
        startObservation()
        acquire()
    }

    private fun acquire() {
        if (!visible || acquisition?.isActive == true) return
        reconnection?.cancel()
        reconnection = null
        updateState { it.copy(refreshing = true, refreshFailure = null) }
        acquisition =
            viewModelScope.launch {
                val result = synchronize(workspaceId)
                ensureActive()
                when (result) {
                    is AppResult.Failed -> {
                        // Another owned metadata write can preempt this round. Resume from its
                        // durable checkpoint without exposing a manual refresh instruction.
                        val preempted = result.failure.code == "client.collaboration_list_changed"
                        updateState {
                            it.copy(
                                refreshing = false,
                                refreshFailure = result.failure.takeUnless { preempted },
                            )
                        }
                        if (preempted || chatCanReconnect(result.failure)) {
                            val wait = chatReconnectDelay(reconnectAttempt, result.failure)
                            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
                            scheduleSync(wait)
                        }
                    }
                    is AppResult.Success -> {
                        reconnectAttempt = 0
                        updateState { it.copy(refreshing = false, refreshFailure = null) }
                        // Healthy local streams stay attached, including pending preview reads.
                        if (
                            observation?.isActive != true ||
                                state.value.failure != null ||
                                state.value.previewFailure != null
                        )
                            startObservation()
                        val wait =
                            if (result.value.caughtUp) 30_000L
                            else result.value.retryAfterSeconds.coerceIn(1, 60) * 1_000L
                        scheduleSync(wait)
                    }
                }
            }
    }

    private fun scheduleSync(delayMillis: Long) {
        if (!visible) return
        val previous = acquisition
        reconnection =
            viewModelScope.launch {
                delay(delayMillis)
                previous?.join()
                ensureActive()
                reconnection = null
                if (visible) acquire()
            }
    }

    private fun onNextRequested(event: ChatInboxEvent.NextRequested) {
        if (!visible || state.value.restricted || state.value.loading) return
        state.value.page?.nextCursor?.let(::nextPage)
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
        if (!visible || state.value.restricted || state.value.loading || previous.isEmpty()) return
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
