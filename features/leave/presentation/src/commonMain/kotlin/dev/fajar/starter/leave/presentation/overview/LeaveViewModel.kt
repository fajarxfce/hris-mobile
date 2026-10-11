package dev.fajar.starter.leave.presentation.overview

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.LeaveSelection
import dev.fajar.starter.leave.domain.policies.leaveFailureInvalidatesCache
import dev.fajar.starter.leave.domain.usecases.ObserveLeave
import dev.fajar.starter.leave.domain.usecases.RefreshLeave
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class LeaveViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveLeave,
    private val refresh: RefreshLeave,
) : MviViewModel<LeaveState, LeaveEvent, LeaveEffect>(LeaveState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null

    init {
        on<LeaveEvent.Entered>(::onEntered)
        on<LeaveEvent.Stopped>(::onStopped)
        on<LeaveEvent.RefreshRequested>(::onRefreshRequested)
        on<LeaveEvent.BackRequested>(::onBackRequested)
        on<LeaveEvent.VerificationRequested>(::onVerificationRequested)
        on<LeaveEvent.YearRequested>(::onYearRequested)
        on<LeaveEvent.StatusFilterRequested>(::onStatusFilterRequested)
        on<LeaveEvent.StatusSelected>(::onStatusSelected)
        on<LeaveEvent.EmploymentFilterRequested>(::onEmploymentFilterRequested)
        on<LeaveEvent.EmploymentSelected>(::onEmploymentSelected)
        on<LeaveEvent.EmploymentPageRequested>(::onEmploymentPageRequested)
        on<LeaveEvent.BalancesPageRequested>(::onBalancesPageRequested)
        on<LeaveEvent.RequestsPageRequested>(::onRequestsPageRequested)
        on<LeaveEvent.DialogDismissed>(::onDialogDismissed)
        on<LeaveEvent.RequestSelected>(::onRequestSelected)
    }

    private fun onEntered(event: LeaveEvent.Entered) {
        if (observation?.isActive == true) return
        updateState { it.copy(loading = true) }
        observeSelection()
        onEvent(LeaveEvent.RefreshRequested)
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
                        else observe(workspaceId).map { selection to it }
                    }
                    .collect { (selection, result) ->
                        ensureActive()
                        if (selection != state.value.selection || state.value.readRestricted)
                            return@collect
                        updateState { it.withCachedLeave(result) }
                    }
            }
    }

    private fun onStopped(event: LeaveEvent.Stopped) {
        observation?.cancel()
        observation = null
        acquisition?.cancel()
        acquisition = null
        updateState { it.copy(content = null, dialog = null, loading = false, refreshing = false) }
    }

    private fun onRefreshRequested(event: LeaveEvent.RefreshRequested) {
        if (observation?.isActive != true || acquisition?.isActive == true) return
        val requested = state.value.selection ?: LeaveSelection()
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
                val result = refresh(workspaceId, requested)
                ensureActive()
                if (result is AppResult.Success) observation?.cancel()
                updateState { it.withLeaveRefreshResult(result) }
                if (result is AppResult.Success) observeSelection()
                if (
                    result is AppResult.Failed &&
                        result.failure.code in workspaceAccessLossCodes &&
                        result.failure.code !in verificationCodes
                )
                    emitEffect(LeaveEffect.RecheckWorkspace)
            }
    }

    private fun onYearRequested(event: LeaveEvent.YearRequested) {
        val selection = state.value.filters ?: return
        val year = selection.year ?: return
        if (event.year !in 1900..2200 || event.year !in setOf(year - 1, year + 1)) return
        changeSelection(selection.copy(year = event.year, balanceAfter = null))
    }

    private fun onStatusFilterRequested(event: LeaveEvent.StatusFilterRequested) {
        if (state.value.filters?.employeeId != null && !state.value.readRestricted)
            updateState { it.copy(dialog = LeaveDialog.STATUS) }
    }

    private fun onStatusSelected(event: LeaveEvent.StatusSelected) {
        val selection = state.value.filters ?: return
        if (state.value.dialog != LeaveDialog.STATUS) return
        updateState { it.copy(dialog = null) }
        if (selection.status != event.status)
            changeSelection(selection.copy(status = event.status, requestAfter = null))
    }

    private fun onEmploymentFilterRequested(event: LeaveEvent.EmploymentFilterRequested) {
        if (state.value.content != null) updateState { it.copy(dialog = LeaveDialog.EMPLOYMENT) }
    }

    private fun onEmploymentSelected(event: LeaveEvent.EmploymentSelected) {
        val content = state.value.content ?: return
        if (
            state.value.dialog != LeaveDialog.EMPLOYMENT ||
                content.directory.items.none { it.id == event.id }
        )
            return
        updateState { it.copy(dialog = null) }
        if (content.selection.employeeId != event.id)
            changeSelection(
                content.selection.copy(
                    employeeId = event.id,
                    balanceAfter = null,
                    requestAfter = null,
                )
            )
    }

    private fun onEmploymentPageRequested(event: LeaveEvent.EmploymentPageRequested) {
        val content = state.value.content ?: return
        if (
            state.value.dialog != LeaveDialog.EMPLOYMENT ||
                (event.after != null && event.after != content.directory.nextCursor)
        )
            return
        changeSelection(
            content.selection.copy(
                employeeId = null,
                employmentAfter = event.after,
                balanceAfter = null,
                requestAfter = null,
            ),
            LeaveDialog.EMPLOYMENT,
        )
    }

    private fun onBalancesPageRequested(event: LeaveEvent.BalancesPageRequested) {
        val content = state.value.content ?: return
        if (
            event.after == content.selection.balanceAfter ||
                (event.after != null && event.after != content.balances.nextCursor)
        )
            return
        changeSelection(content.selection.copy(balanceAfter = event.after))
    }

    private fun onRequestsPageRequested(event: LeaveEvent.RequestsPageRequested) {
        val content = state.value.content ?: return
        if (
            event.after == content.selection.requestAfter ||
                (event.after != null && event.after != content.requests.nextCursor)
        )
            return
        changeSelection(content.selection.copy(requestAfter = event.after))
    }

    private fun changeSelection(selection: LeaveSelection, dialog: LeaveDialog? = null) {
        if (observation?.isActive != true || state.value.readRestricted) return
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                selection = selection,
                content = null,
                dialog = dialog,
                loading = true,
                refreshing = false,
                cacheFailure = it.cacheFailure?.takeIf(::leaveFailureInvalidatesCache),
                refreshFailure = it.refreshFailure?.takeIf(::leaveFailureInvalidatesCache),
            )
        }
        onEvent(LeaveEvent.RefreshRequested)
    }

    private fun onDialogDismissed(event: LeaveEvent.DialogDismissed) {
        updateState { it.copy(dialog = null) }
    }

    private fun onRequestSelected(event: LeaveEvent.RequestSelected) {
        if (
            state.value.readRestricted ||
                state.value.content?.requests?.items?.none { it.id == event.id } != false
        )
            return
        viewModelScope.launch { emitEffect(LeaveEffect.OpenRequest(event.id)) }
    }

    private fun onBackRequested(event: LeaveEvent.BackRequested) {
        viewModelScope.launch { emitEffect(LeaveEffect.Back) }
    }

    private fun onVerificationRequested(event: LeaveEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(LeaveEffect.VerifyAccount) }
    }
}
