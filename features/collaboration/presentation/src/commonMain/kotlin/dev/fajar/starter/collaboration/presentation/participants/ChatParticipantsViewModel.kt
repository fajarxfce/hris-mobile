package dev.fajar.starter.collaboration.presentation.participants

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ChatParticipantsViewModel(
    @InjectedParam private val workspaceId: String,
    private val observeAccess: ObserveCollaborationAccess,
    private val read: ReadCollaborationConversations,
    private val prepare: PrepareCollaborationConversation,
    private val replay: ReplayCollaborationConversation,
    private val refresh: RefreshCollaborationList,
    private val reconcile: ReconcileCollaborationConversation,
) :
    MviViewModel<ChatParticipantsState, ChatParticipantsEvent, ChatParticipantsEffect>(
        ChatParticipantsState()
    ) {
    private var observation: Job? = null
    private var acquisition: Job? = null
    private var opening: Job? = null
    private var visible = false
    private var accessObserved = false
    private var reloadOnEntry = true

    init {
        on<ChatParticipantsEvent.Entered>(::onEntered)
        on<ChatParticipantsEvent.Stopped>(::onStopped)
        on<ChatParticipantsEvent.BackRequested>(::onBackRequested)
        on<ChatParticipantsEvent.QueryChanged>(::onQueryChanged)
        on<ChatParticipantsEvent.RetryRequested>(::onRetryRequested)
        on<ChatParticipantsEvent.NextRequested>(::onNextRequested)
        on<ChatParticipantsEvent.PreviousRequested>(::onPreviousRequested)
        on<ChatParticipantsEvent.ParticipantSelected>(::onParticipantSelected)
        on<ChatParticipantsEvent.RetryOpenRequested>(::onRetryOpenRequested)
        on<ChatParticipantsEvent.CancelSelectionRequested>(::onCancelSelectionRequested)
    }

    private fun onEntered(event: ChatParticipantsEvent.Entered) {
        if (visible) return
        visible = true
        if (observation?.isActive != true) startObservation(acquireOnFirst = true)
        else if (accessObserved && reloadOnEntry && state.value.selected == null) acquire()
    }

    private fun startObservation(acquireOnFirst: Boolean) {
        accessObserved = false
        observation?.cancel()
        observation =
            viewModelScope.launch {
                var first = true
                observeAccess(workspaceId).collect { result ->
                    ensureActive()
                    accessObserved = true
                    when (result) {
                        is AppResult.Failed -> {
                            acquisition?.cancel()
                            acquisition = null
                            opening?.cancel()
                            opening = null
                            reloadOnEntry = true
                            updateState {
                                ChatParticipantsState(
                                    query = it.query,
                                    loading = false,
                                    localFailure = result.failure,
                                )
                            }
                        }
                        is AppResult.Success -> updateState { it.copy(localFailure = null) }
                    }
                    if (first) {
                        first = false
                        if (visible && acquireOnFirst && reloadOnEntry) acquire()
                    }
                }
            }
    }

    private fun onStopped(event: ChatParticipantsEvent.Stopped) {
        visible = false
        acquisition?.cancel()
        acquisition = null
        opening?.cancel()
        opening = null
        updateState { it.copy(loading = false, opening = false) }
    }

    private fun onBackRequested(event: ChatParticipantsEvent.BackRequested) {
        if (!visible) return
        onStopped(ChatParticipantsEvent.Stopped)
        viewModelScope.launch { emitEffect(ChatParticipantsEffect.Back) }
    }

    private fun onQueryChanged(event: ChatParticipantsEvent.QueryChanged) {
        if (!visible || state.value.selected != null) return
        val bounded =
            event.value.take(80).let {
                if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
            }
        val query = bounded.map { if (it.isISOControl()) ' ' else it }.joinToString("")
        if (query == state.value.query) return
        updateState {
            it.copy(
                query = query,
                page = null,
                after = null,
                previous = emptyList(),
                failure = null,
            )
        }
        acquire(debounceMillis = 300)
        viewModelScope.launch { emitEffect(ChatParticipantsEffect.ScrollToStart) }
    }

    private fun acquire(debounceMillis: Long = 0) {
        if (!visible || state.value.selected != null) return
        acquisition?.cancel()
        reloadOnEntry = true
        val query = state.value.query
        val after = state.value.after
        updateState { it.copy(loading = true, failure = null) }
        acquisition =
            viewModelScope.launch {
                if (debounceMillis > 0) delay(debounceMillis)
                val result = read.participants(workspaceId, query.trim(), after)
                ensureActive()
                when (result) {
                    is AppResult.Failed ->
                        updateState { it.copy(loading = false, failure = result.failure) }
                    is AppResult.Success -> {
                        reloadOnEntry = false
                        updateState {
                            it.copy(
                                loading = false,
                                page = result.value.value,
                                localFailure = null,
                                failure = null,
                            )
                        }
                        startObservation(acquireOnFirst = false)
                    }
                }
            }
    }

    private fun onRetryRequested(event: ChatParticipantsEvent.RetryRequested) {
        if (acquisition?.isActive == true) return
        acquire()
    }

    private fun onNextRequested(event: ChatParticipantsEvent.NextRequested) {
        val current = state.value
        if (
            !visible ||
                current.loading ||
                current.localFailure != null ||
                current.selected != null ||
                current.previous.size >= 200
        )
            return
        val next = current.page?.nextCursor ?: return
        updateState {
            it.copy(after = next, previous = it.previous + listOf(it.after), page = null)
        }
        acquire()
        viewModelScope.launch { emitEffect(ChatParticipantsEffect.ScrollToStart) }
    }

    private fun onPreviousRequested(event: ChatParticipantsEvent.PreviousRequested) {
        val current = state.value
        if (!visible || current.loading || current.selected != null || current.previous.isEmpty())
            return
        updateState {
            it.copy(after = it.previous.last(), previous = it.previous.dropLast(1), page = null)
        }
        acquire()
        viewModelScope.launch { emitEffect(ChatParticipantsEffect.ScrollToStart) }
    }

    private fun onParticipantSelected(event: ChatParticipantsEvent.ParticipantSelected) {
        val current = state.value
        if (!visible || current.localFailure != null || current.loading || current.selected != null)
            return
        val person = current.page?.items?.singleOrNull { it.accountId == event.accountId } ?: return
        updateState { it.copy(selected = person) }
        openSelected()
    }

    private fun onRetryOpenRequested(event: ChatParticipantsEvent.RetryOpenRequested) {
        if (!visible || state.value.localFailure != null || state.value.opening) return
        val ready = state.value.openedConversationId
        if (ready != null) {
            onStopped(ChatParticipantsEvent.Stopped)
            viewModelScope.launch { emitEffect(ChatParticipantsEffect.OpenConversation(ready)) }
        } else openSelected()
    }

    private fun onCancelSelectionRequested(event: ChatParticipantsEvent.CancelSelectionRequested) {
        if (!visible) return
        opening?.cancel()
        opening = null
        updateState {
            it.copy(
                selected = null,
                opening = false,
                openFailure = null,
                openedConversationId = null,
            )
        }
    }

    private fun openSelected() {
        val person = state.value.selected ?: return
        if (!visible || opening?.isActive == true) return
        acquisition?.cancel()
        acquisition = null
        updateState { it.copy(opening = true, openFailure = null) }
        opening =
            viewModelScope.launch {
                val prepared =
                    prepare(workspaceId, CollaborationConversationAction.Open(person.accountId))
                ensureActive()
                if (prepared is AppResult.Failed) return@launch openFailed(prepared.failure)
                val delivered =
                    replay(workspaceId, (prepared as AppResult.Success).value.command.id)
                ensureActive()
                if (delivered is AppResult.Failed) return@launch openFailed(delivered.failure)
                val record = (delivered as AppResult.Success).value
                val receipt =
                    (record.outcome as? CollaborationConversationOutcome.Confirmed)?.receipt
                        ?: return@launch openFailed(changed())
                val acquired = refresh.conversation(workspaceId, receipt.id)
                ensureActive()
                if (acquired is AppResult.Failed) return@launch openFailed(acquired.failure)
                val completed = reconcile(workspaceId, record.command.id)
                ensureActive()
                if (completed is AppResult.Failed) return@launch openFailed(completed.failure)
                if (!(completed as AppResult.Success).value) return@launch openFailed(changed())
                updateState { it.copy(opening = false, openedConversationId = receipt.id) }
                opening = null
                visible = false
                emitEffect(ChatParticipantsEffect.OpenConversation(receipt.id))
            }
    }

    private fun openFailed(failure: Failure) {
        updateState { it.copy(opening = false, openFailure = failure) }
    }

    private fun changed() =
        Failure(
            FailureKind.Conflict,
            "Open the conversation again.",
            code = "client.collaboration_command_changed",
        )
}
