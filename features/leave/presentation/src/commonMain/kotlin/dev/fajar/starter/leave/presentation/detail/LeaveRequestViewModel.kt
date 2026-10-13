package dev.fajar.starter.leave.presentation.detail

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.usecases.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class LeaveRequestViewModel(
    @InjectedParam private val target: LeaveRequestTarget,
    private val observe: ObserveLeaveRequest,
    private val refresh: RefreshLeaveRequest,
    private val observeAction: ObserveLeaveAction,
    private val prepareAction: PrepareLeaveAction,
    private val replayAction: ReplayLeaveAction,
    private val reviewAction: ReviewLeaveAction,
) : MviViewModel<LeaveRequestState, LeaveRequestEvent, LeaveRequestEffect>(LeaveRequestState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null
    private var commandObservation: Job? = null
    private var action: Job? = null

    init {
        on<LeaveRequestEvent.Entered>(::onEntered)
        on<LeaveRequestEvent.Stopped>(::onStopped)
        on<LeaveRequestEvent.RefreshRequested>(::onRefreshRequested)
        on<LeaveRequestEvent.HistoryPageRequested>(::onHistoryPageRequested)
        on<LeaveRequestEvent.BackRequested>(::onBackRequested)
        on<LeaveRequestEvent.VerificationRequested>(::onVerificationRequested)
        on<LeaveRequestEvent.ActionRequested>(::onActionRequested)
        on<LeaveRequestEvent.ReasonChanged>(::onReasonChanged)
        on<LeaveRequestEvent.ActionConfirmed>(::onActionConfirmed)
        on<LeaveRequestEvent.ActionDismissed>(::onActionDismissed)
        on<LeaveRequestEvent.RetryActionRequested>(::onRetryActionRequested)
        on<LeaveRequestEvent.ReviewActionRequested>(::onReviewActionRequested)
        on<LeaveRequestEvent.DiscardActionRequested>(::onDiscardActionRequested)
    }

    private fun onEntered(event: LeaveRequestEvent.Entered) {
        if (observation?.isActive == true) return
        updateState { it.copy(loading = true) }
        observeSelection()
        observeCommand()
        onEvent(LeaveRequestEvent.RefreshRequested)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCommand() {
        commandObservation?.cancel()
        commandObservation =
            viewModelScope.launch {
                state
                    .map { it.readRestricted }
                    .distinctUntilChanged()
                    .flatMapLatest { restricted ->
                        if (restricted) emptyFlow()
                        else observeAction(target.workspaceId, target.requestId)
                    }
                    .collect { result ->
                        ensureActive()
                        if (!state.value.readRestricted)
                            updateState { it.withObservedLeaveAction(result) }
                    }
            }
    }

    /** Explicit refresh also reopens a storage flow that previously completed after an error. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSelection() {
        observation?.cancel()
        observation =
            viewModelScope.launch {
                state
                    .map { it.selection to it.readRestricted }
                    .distinctUntilChanged()
                    .flatMapLatest { (selection, restricted) ->
                        if (restricted) emptyFlow()
                        else observe(target.workspaceId, target.requestId).map { selection to it }
                    }
                    .collect { (selection, result) ->
                        ensureActive()
                        if (selection != state.value.selection || state.value.readRestricted)
                            return@collect
                        updateState { it.withCachedRequest(result) }
                    }
            }
    }

    private fun onStopped(event: LeaveRequestEvent.Stopped) {
        observation?.cancel()
        observation = null
        acquisition?.cancel()
        acquisition = null
        commandObservation?.cancel()
        commandObservation = null
        action?.cancel()
        action = null
        updateState {
            it.copy(
                content = null,
                loading = false,
                refreshing = false,
                cacheFailure =
                    it.failure?.takeIf(::leaveFailureInvalidatesCache) ?: it.cacheFailure,
                action = LeaveActionState(),
            )
        }
    }

    private fun onRefreshRequested(event: LeaveRequestEvent.RefreshRequested) {
        if (
            observation?.isActive != true ||
                acquisition?.isActive == true ||
                action?.isActive == true
        )
            return
        val requested = state.value.selection ?: LeaveRequestSelection(target.requestId)
        acquisition =
            viewModelScope.launch {
                if (refreshContent(requested)) {
                    val retained = state.value.action.command
                    if (retained?.outcome is LeaveCommandOutcome.Confirmed)
                        retireAction(retained.command.id)
                }
            }
    }

    private suspend fun refreshContent(requested: LeaveRequestSelection): Boolean {
        updateState {
            it.copy(
                refreshing = true,
                loading = it.content == null,
                cacheFailure =
                    it.refreshFailure?.takeIf(::leaveFailureInvalidatesCache) ?: it.cacheFailure,
                refreshFailure = it.refreshFailure?.takeIf(::leaveFailureInvalidatesCache),
                action =
                    it.action.copy(
                        form = null,
                        failure = it.action.failure?.takeIf(::leaveFailureInvalidatesCache),
                    ),
            )
        }
        val result = refresh(target.workspaceId, requested)
        currentCoroutineContext().ensureActive()
        if (result is AppResult.Success) {
            observation?.cancel()
            commandObservation?.cancel()
        }
        updateState { it.withRequestRefreshResult(result) }
        if (result is AppResult.Success) {
            observeSelection()
            observeCommand()
        } else if (
            result is AppResult.Failed &&
                result.failure.code in workspaceAccessLossCodes &&
                result.failure.code !in requestVerificationCodes
        )
            emitEffect(LeaveRequestEffect.RecheckWorkspace)
        return result is AppResult.Success
    }

    private fun onHistoryPageRequested(event: LeaveRequestEvent.HistoryPageRequested) {
        if (
            observation?.isActive != true ||
                state.value.readRestricted ||
                state.value.action.working
        )
            return
        val selection = state.value.visibleSelection ?: return
        val after = event.after?.toLongOrNull()
        if (
            event.after != null &&
                (after == null || event.after != state.value.content?.request?.history?.nextCursor)
        )
            return
        if (after == selection.historyAfter) return
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                selection = selection.copy(historyAfter = after),
                content = null,
                loading = true,
                refreshing = false,
                refreshFailure = null,
                action = it.action.copy(form = null),
            )
        }
        onEvent(LeaveRequestEvent.RefreshRequested)
    }

    private fun onActionRequested(event: LeaveRequestEvent.ActionRequested) {
        if (event.action !in state.value.availableActions) return
        val version = state.value.content?.request?.version ?: return
        updateState {
            it.copy(
                action =
                    it.action.copy(form = LeaveActionForm(event.action, version), failure = null)
            )
        }
    }

    private fun onReasonChanged(event: LeaveRequestEvent.ReasonChanged) {
        val form = state.value.action.form ?: return
        if (state.value.action.working) return
        updateState {
            it.copy(
                action =
                    it.action.copy(
                        form = form.copy(reason = event.value.take(1_001), failure = null)
                    )
            )
        }
    }

    private fun onActionDismissed(event: LeaveRequestEvent.ActionDismissed) {
        updateState { it.copy(action = it.action.copy(form = null)) }
    }

    private fun onActionConfirmed(event: LeaveRequestEvent.ActionConfirmed) {
        val form = state.value.action.form ?: return
        if (
            form.action !in state.value.availableActions ||
                action?.isActive == true ||
                acquisition?.isActive == true
        )
            return
        updateState { it.copy(action = it.action.copy(working = true, failure = null)) }
        action =
            viewModelScope.launch {
                try {
                    val prepared =
                        prepareAction(
                            target.workspaceId,
                            target.requestId,
                            form.version,
                            form.action,
                            form.reason,
                        )
                    ensureActive()
                    when (prepared) {
                        is AppResult.Failed -> {
                            if (
                                !leaveFailureInvalidatesCache(prepared.failure) &&
                                    state.value.action.form != null
                            )
                                updateState {
                                    it.copy(
                                        action =
                                            it.action.copy(
                                                form = form.copy(failure = prepared.failure)
                                            )
                                    )
                                }
                            else updateState { it.withLeaveActionFailure(prepared.failure) }
                        }
                        is AppResult.Success -> {
                            updateState { it.withObservedLeaveAction(prepared) }
                            sendAndRefresh(prepared.value.command.id)
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(action = it.action.copy(working = false)) }
                }
            }
    }

    private fun onRetryActionRequested(event: LeaveRequestEvent.RetryActionRequested) {
        val command = state.value.action.command ?: return
        if (
            !state.value.canRetryAction || action?.isActive == true || acquisition?.isActive == true
        )
            return
        updateState { it.copy(action = it.action.copy(working = true, failure = null)) }
        action =
            viewModelScope.launch {
                try {
                    sendAndRefresh(command.command.id)
                } finally {
                    if (isActive) updateState { it.copy(action = it.action.copy(working = false)) }
                }
            }
    }

    private suspend fun sendAndRefresh(id: String) {
        val result = replayAction(target.workspaceId, id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Failed -> updateState { it.withLeaveActionFailure(result.failure) }
            is AppResult.Success -> {
                updateState { it.withObservedLeaveAction(result) }
                when (val outcome = result.value.outcome) {
                    is LeaveCommandOutcome.Confirmed -> {
                        val latest = LeaveRequestSelection(target.requestId)
                        updateState { it.copy(selection = latest) }
                        if (refreshContent(latest)) retireAction(id)
                    }
                    is LeaveCommandOutcome.Unconfirmed ->
                        outcome.failure?.let { failure ->
                            updateState { it.withLeaveActionFailure(failure) }
                        }
                    is LeaveCommandOutcome.Rejected ->
                        updateState { it.withLeaveActionFailure(outcome.failure) }
                    LeaveCommandOutcome.Pending -> Unit
                }
            }
        }
    }

    private fun onReviewActionRequested(event: LeaveRequestEvent.ReviewActionRequested) {
        val command = state.value.action.command ?: return
        if (
            !state.value.canReviewAction ||
                action?.isActive == true ||
                acquisition?.isActive == true
        )
            return
        val latest = LeaveRequestSelection(target.requestId)
        updateState {
            it.copy(selection = latest, action = it.action.copy(working = true, failure = null))
        }
        action =
            viewModelScope.launch {
                try {
                    if (refreshContent(latest)) retireAction(command.command.id)
                } finally {
                    if (isActive) updateState { it.copy(action = it.action.copy(working = false)) }
                }
            }
    }

    private fun onDiscardActionRequested(event: LeaveRequestEvent.DiscardActionRequested) {
        val command = state.value.action.command ?: return
        if (
            !state.value.canDiscardAction ||
                action?.isActive == true ||
                acquisition?.isActive == true
        )
            return
        updateState { it.copy(action = it.action.copy(working = true, failure = null)) }
        action =
            viewModelScope.launch {
                try {
                    retireAction(command.command.id)
                } finally {
                    if (isActive) updateState { it.copy(action = it.action.copy(working = false)) }
                }
            }
    }

    private suspend fun retireAction(id: String) {
        val result = reviewAction(target.workspaceId, id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Failed -> updateState { it.withLeaveActionFailure(result.failure) }
            is AppResult.Success ->
                if (result.value) {
                    commandObservation?.cancel()
                    updateState {
                        it.copy(
                            action = it.action.copy(command = null, ready = false, failure = null)
                        )
                    }
                    observeCommand()
                }
        }
    }

    private fun onBackRequested(event: LeaveRequestEvent.BackRequested) {
        if (state.value.action.form != null) onEvent(LeaveRequestEvent.ActionDismissed)
        else viewModelScope.launch { emitEffect(LeaveRequestEffect.Back) }
    }

    private fun onVerificationRequested(event: LeaveRequestEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(LeaveRequestEffect.VerifyAccount) }
    }
}
