package dev.fajar.starter.leave.presentation.detail

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.LeaveRequestSelection
import dev.fajar.starter.leave.domain.policies.leaveFailureInvalidatesCache
import dev.fajar.starter.leave.domain.usecases.ObserveLeaveRequest
import dev.fajar.starter.leave.domain.usecases.RefreshLeaveRequest
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
) : MviViewModel<LeaveRequestState, LeaveRequestEvent, LeaveRequestEffect>(LeaveRequestState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null

    init {
        on<LeaveRequestEvent.Entered>(::onEntered)
        on<LeaveRequestEvent.Stopped>(::onStopped)
        on<LeaveRequestEvent.RefreshRequested>(::onRefreshRequested)
        on<LeaveRequestEvent.HistoryPageRequested>(::onHistoryPageRequested)
        on<LeaveRequestEvent.BackRequested>(::onBackRequested)
        on<LeaveRequestEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: LeaveRequestEvent.Entered) {
        if (observation?.isActive == true) return
        updateState { it.copy(loading = true) }
        observeSelection()
        onEvent(LeaveRequestEvent.RefreshRequested)
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
        updateState { it.copy(content = null, loading = false, refreshing = false) }
    }

    private fun onRefreshRequested(event: LeaveRequestEvent.RefreshRequested) {
        if (observation?.isActive != true || acquisition?.isActive == true) return
        val requested = state.value.selection ?: LeaveRequestSelection(target.requestId)
        updateState {
            it.copy(
                refreshing = true,
                cacheFailure =
                    it.refreshFailure?.takeIf(::leaveFailureInvalidatesCache) ?: it.cacheFailure,
                refreshFailure = it.refreshFailure?.takeIf(::leaveFailureInvalidatesCache),
            )
        }
        acquisition =
            viewModelScope.launch {
                val result = refresh(target.workspaceId, requested)
                ensureActive()
                if (result is AppResult.Success) observation?.cancel()
                updateState { it.withRequestRefreshResult(result) }
                if (result is AppResult.Success) observeSelection()
                else if (
                    result is AppResult.Failed &&
                        result.failure.code in workspaceAccessLossCodes &&
                        result.failure.code !in requestVerificationCodes
                ) {
                    emitEffect(LeaveRequestEffect.RecheckWorkspace)
                }
            }
    }

    private fun onHistoryPageRequested(event: LeaveRequestEvent.HistoryPageRequested) {
        if (observation?.isActive != true || state.value.readRestricted) return
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
            )
        }
        onEvent(LeaveRequestEvent.RefreshRequested)
    }

    private fun onBackRequested(event: LeaveRequestEvent.BackRequested) {
        viewModelScope.launch { emitEffect(LeaveRequestEffect.Back) }
    }

    private fun onVerificationRequested(event: LeaveRequestEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(LeaveRequestEffect.VerifyAccount) }
    }
}
