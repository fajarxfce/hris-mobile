package dev.fajar.starter.workforce.presentation.overtime.detail

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailureInvalidatesCache
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import dev.fajar.starter.workforce.presentation.overtime.verificationCodes
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class OvertimeRequestViewModel(
    @InjectedParam private val target: OvertimeRequestTarget,
    private val observe: ObserveOvertimeRequest,
    private val refresh: RefreshOvertimeRequest,
) :
    MviViewModel<OvertimeRequestState, OvertimeRequestEvent, OvertimeRequestEffect>(
        OvertimeRequestState(target.selection)
    ) {
    private var observation: Job? = null
    private var acquisition: Job? = null

    init {
        on<OvertimeRequestEvent.Entered>(::onEntered)
        on<OvertimeRequestEvent.Stopped>(::onStopped)
        on<OvertimeRequestEvent.RefreshRequested>(::onRefreshRequested)
        on<OvertimeRequestEvent.BackRequested>(::onBackRequested)
        on<OvertimeRequestEvent.VerificationRequested>(::onVerificationRequested)
        on<OvertimeRequestEvent.HistoryPageRequested>(::onHistoryPageRequested)
        on<OvertimeRequestEvent.ActionRequested>(::onActionRequested)
    }

    private fun onEntered(event: OvertimeRequestEvent.Entered) {
        if (observation?.isActive == true) return
        updateState { it.copy(loading = true) }
        observeSelection()
        onEvent(OvertimeRequestEvent.RefreshRequested)
    }

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
                        else observe(target.workspaceId).map { selection to it }
                    }
                    .collect { (selection, result) ->
                        ensureActive()
                        if (selection == state.value.selection && !state.value.readRestricted)
                            updateState { it.withCachedRequest(result) }
                    }
            }
    }

    private fun onStopped(event: OvertimeRequestEvent.Stopped) {
        observation?.cancel()
        observation = null
        acquisition?.cancel()
        acquisition = null
        updateState { it.copy(content = null, loading = false, refreshing = false) }
    }

    private fun onRefreshRequested(event: OvertimeRequestEvent.RefreshRequested) {
        if (observation?.isActive != true || acquisition?.isActive == true) return
        val requested = state.value.selection
        updateState {
            it.copy(
                refreshing = true,
                cacheFailure =
                    it.refreshFailure?.takeIf(::overtimeFailureInvalidatesCache) ?: it.cacheFailure,
                refreshFailure = it.refreshFailure?.takeIf(::overtimeFailureInvalidatesCache),
            )
        }
        acquisition =
            viewModelScope.launch {
                val result = refresh(target.workspaceId, requested)
                ensureActive()
                if (result is AppResult.Success) observation?.cancel()
                updateState { it.withRequestRefresh(result) }
                if (result is AppResult.Success) observeSelection()
                if (
                    result is AppResult.Failed &&
                        result.failure.code in workspaceAccessLossCodes &&
                        result.failure.code !in verificationCodes
                )
                    emitEffect(OvertimeRequestEffect.RecheckWorkspace)
            }
    }

    private fun onHistoryPageRequested(event: OvertimeRequestEvent.HistoryPageRequested) {
        val content = state.value.content?.value
        if (
            state.value.readRestricted ||
                event.after == state.value.selection.historyAfter ||
                (event.after != null &&
                    event.after != content?.details?.history?.nextCursor?.toLongOrNull())
        )
            return
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                selection = it.selection.copy(historyAfter = event.after),
                content = null,
                loading = true,
                refreshing = false,
            )
        }
        onEvent(OvertimeRequestEvent.RefreshRequested)
    }

    private fun onBackRequested(event: OvertimeRequestEvent.BackRequested) {
        viewModelScope.launch { emitEffect(OvertimeRequestEffect.Back) }
    }

    private fun onActionRequested(event: OvertimeRequestEvent.ActionRequested) {
        if (!state.value.canOpenAction || event.kind !in state.value.actionOptions) return
        val selection = state.value.selection.copy(historyAfter = null)
        viewModelScope.launch {
            emitEffect(OvertimeRequestEffect.OpenAction(selection, event.kind))
        }
    }

    private fun onVerificationRequested(event: OvertimeRequestEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(OvertimeRequestEffect.VerifyAccount) }
    }
}
