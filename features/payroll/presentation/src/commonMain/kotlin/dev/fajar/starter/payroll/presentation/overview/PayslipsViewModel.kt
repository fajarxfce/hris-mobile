package dev.fajar.starter.payroll.presentation.overview

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.payroll.domain.entities.PayslipSelection
import dev.fajar.starter.payroll.domain.policies.payslipFailureInvalidatesCache
import dev.fajar.starter.payroll.domain.usecases.*
import dev.fajar.starter.payroll.presentation.payslipVerificationCodes
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class PayslipsViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObservePayslips,
    private val refresh: RefreshPayslips,
) : MviViewModel<PayslipsState, PayslipsEvent, PayslipsEffect>(PayslipsState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null

    init {
        on<PayslipsEvent.Entered>(::onEntered)
        on<PayslipsEvent.Stopped>(::onStopped)
        on<PayslipsEvent.RefreshRequested>(::onRefreshRequested)
        on<PayslipsEvent.PreviousYearRequested>(::onPreviousYearRequested)
        on<PayslipsEvent.NextYearRequested>(::onNextYearRequested)
        on<PayslipsEvent.PageRequested>(::onPageRequested)
        on<PayslipsEvent.PayslipSelected>(::onPayslipSelected)
        on<PayslipsEvent.BackRequested>(::onBackRequested)
        on<PayslipsEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: PayslipsEvent.Entered) {
        if (observation?.isActive == true) return
        updateState { it.copy(loading = true) }
        observeSelection()
        onEvent(PayslipsEvent.RefreshRequested)
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
                        updateState { it.withCachedPayslips(result) }
                    }
            }
    }

    private fun onStopped(event: PayslipsEvent.Stopped) {
        observation?.cancel()
        observation = null
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(selection = it.filters, content = null, loading = false, refreshing = false)
        }
    }

    private fun onRefreshRequested(event: PayslipsEvent.RefreshRequested) {
        if (observation?.isActive != true || acquisition?.isActive == true) return
        val requested = state.value.filters ?: PayslipSelection()
        updateState {
            it.copy(
                refreshing = true,
                loading = it.content == null,
                cacheFailure =
                    it.refreshFailure?.takeIf(::payslipFailureInvalidatesCache) ?: it.cacheFailure,
                refreshFailure = it.refreshFailure?.takeIf(::payslipFailureInvalidatesCache),
            )
        }
        acquisition =
            viewModelScope.launch {
                val result = refresh(workspaceId, requested)
                ensureActive()
                if (result is AppResult.Success) observation?.cancel()
                updateState { it.withPayslipsRefresh(result, requested) }
                if (result is AppResult.Success) observeSelection()
                if (
                    result is AppResult.Failed &&
                        result.failure.code in workspaceAccessLossCodes &&
                        result.failure.code !in payslipVerificationCodes
                )
                    emitEffect(PayslipsEffect.RecheckWorkspace)
            }
    }

    private fun onPreviousYearRequested(event: PayslipsEvent.PreviousYearRequested) {
        val year = state.value.filters?.year ?: return
        if (year > 2024) select(PayslipSelection(year - 1))
    }

    private fun onNextYearRequested(event: PayslipsEvent.NextYearRequested) {
        val year = state.value.filters?.year ?: return
        if (year < 2100) select(PayslipSelection(year + 1))
    }

    private fun onPageRequested(event: PayslipsEvent.PageRequested) {
        val current = state.value.content?.value ?: return
        if (
            event.after == current.selection.after ||
                (event.after != null && event.after != current.page.nextCursor)
        )
            return
        select(current.selection.copy(after = event.after))
    }

    private fun select(selection: PayslipSelection) {
        if (observation?.isActive != true || state.value.readRestricted) return
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                selection = selection,
                content = null,
                loading = true,
                refreshing = false,
                cacheFailure = null,
                refreshFailure = null,
            )
        }
        onEvent(PayslipsEvent.RefreshRequested)
    }

    private fun onPayslipSelected(event: PayslipsEvent.PayslipSelected) {
        val current = state.value.content?.value ?: return
        if (state.value.readRestricted || current.page.items.none { it.id == event.id }) return
        viewModelScope.launch { emitEffect(PayslipsEffect.OpenPayslip(event.id)) }
    }

    private fun onBackRequested(event: PayslipsEvent.BackRequested) {
        viewModelScope.launch { emitEffect(PayslipsEffect.Back) }
    }

    private fun onVerificationRequested(event: PayslipsEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(PayslipsEffect.VerifyAccount) }
    }
}
