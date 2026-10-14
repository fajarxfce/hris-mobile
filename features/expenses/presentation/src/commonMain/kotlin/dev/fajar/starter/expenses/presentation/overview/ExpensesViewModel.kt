package dev.fajar.starter.expenses.presentation.overview

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.ExpenseSelection
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.usecases.SyncExpenses
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.expenses.presentation.expenseVerificationCodes
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ExpensesViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveExpenses,
    private val sync: SyncExpenses,
) : MviViewModel<ExpensesState, ExpensesEvent, ExpensesEffect>(ExpensesState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null

    init {
        on<ExpensesEvent.CreateRequested>(::onCreateRequested)
        on<ExpensesEvent.Entered>(::onEntered)
        on<ExpensesEvent.Stopped>(::onStopped)
        on<ExpensesEvent.RefreshRequested>(::onRefreshRequested)
        on<ExpensesEvent.PreviousMonthRequested>(::onPreviousMonthRequested)
        on<ExpensesEvent.NextMonthRequested>(::onNextMonthRequested)
        on<ExpensesEvent.FilterOpened>(::onFilterOpened)
        on<ExpensesEvent.FilterDismissed>(::onFilterDismissed)
        on<ExpensesEvent.PickerYearRequested>(::onPickerYearRequested)
        on<ExpensesEvent.MonthSelected>(::onMonthSelected)
        on<ExpensesEvent.StatusSelected>(::onStatusSelected)
        on<ExpensesEvent.EmploymentSelected>(::onEmploymentSelected)
        on<ExpensesEvent.EmploymentPageRequested>(::onEmploymentPageRequested)
        on<ExpensesEvent.PageRequested>(::onPageRequested)
        on<ExpensesEvent.ClaimSelected>(::onClaimSelected)
        on<ExpensesEvent.BackRequested>(::onBackRequested)
        on<ExpensesEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: ExpensesEvent.Entered) {
        if (observation?.isActive == true) return
        updateState { it.copy(loading = true) }
        observeSelection()
        onEvent(ExpensesEvent.RefreshRequested)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSelection() {
        observation?.cancel()
        observation =
            viewModelScope.launch {
                state
                    .map { it.selection }
                    .distinctUntilChanged()
                    .flatMapLatest { selection ->
                        observe(workspaceId, selection ?: ExpenseSelection()).map {
                            selection to it
                        }
                    }
                    .collect { (selection, result) ->
                        ensureActive()
                        if (selection != state.value.selection) return@collect
                        updateState { it.withCachedExpenses(result) }
                    }
            }
    }

    private fun onStopped(event: ExpensesEvent.Stopped) {
        observation?.cancel()
        observation = null
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                selection = it.filters,
                content = null,
                loading = false,
                refreshing = false,
                dialog = null,
            )
        }
    }

    private fun onRefreshRequested(event: ExpensesEvent.RefreshRequested) {
        if (observation?.isActive != true || acquisition?.isActive == true) return
        updateState {
            it.copy(
                refreshing = true,
                loading = it.content == null,
                cacheFailure =
                    it.refreshFailure?.takeIf(::expenseFailureInvalidatesCache) ?: it.cacheFailure,
                refreshFailure = it.refreshFailure?.takeIf(::expenseFailureInvalidatesCache),
            )
        }
        acquisition =
            viewModelScope.launch {
                val result = sync(workspaceId)
                ensureActive()
                if (result is AppResult.Success) observation?.cancel()
                updateState { it.withExpensesRefresh(result) }
                if (result is AppResult.Success) observeSelection()
                if (
                    result is AppResult.Failed &&
                        result.failure.code in workspaceAccessLossCodes &&
                        result.failure.code !in expenseVerificationCodes
                )
                    emitEffect(ExpensesEffect.RecheckWorkspace)
            }
    }

    private fun onPreviousMonthRequested(event: ExpensesEvent.PreviousMonthRequested) {
        val selection = state.value.filters ?: return
        val month = selection.month ?: return
        if (month > YearMonth(1900, 1))
            select(selection.copy(month = month.minus(1, DateTimeUnit.MONTH), after = null))
    }

    private fun onNextMonthRequested(event: ExpensesEvent.NextMonthRequested) {
        val selection = state.value.filters ?: return
        val month = selection.month ?: return
        if (month < YearMonth(2200, 12))
            select(selection.copy(month = month.plus(1, DateTimeUnit.MONTH), after = null))
    }

    private fun onFilterOpened(event: ExpensesEvent.FilterOpened) {
        if (state.value.content == null || state.value.readRestricted) return
        updateState { it.copy(dialog = event.filter, pickerYear = it.filters?.month?.year) }
    }

    private fun onFilterDismissed(event: ExpensesEvent.FilterDismissed) {
        updateState { it.copy(dialog = null) }
    }

    private fun onPickerYearRequested(event: ExpensesEvent.PickerYearRequested) {
        if (state.value.dialog == ExpenseFilter.MONTH && event.year in 1900..2200)
            updateState { it.copy(pickerYear = event.year) }
    }

    private fun onMonthSelected(event: ExpensesEvent.MonthSelected) {
        if (state.value.dialog != ExpenseFilter.MONTH || event.month.year !in 1900..2200) return
        val selection = state.value.filters ?: return
        updateState { it.copy(dialog = null) }
        select(selection.copy(month = event.month, after = null))
    }

    private fun onStatusSelected(event: ExpensesEvent.StatusSelected) {
        if (state.value.dialog != ExpenseFilter.STATUS) return
        val selection = state.value.filters ?: return
        updateState { it.copy(dialog = null) }
        select(selection.copy(status = event.status, after = null))
    }

    private fun onEmploymentSelected(event: ExpensesEvent.EmploymentSelected) {
        val current = state.value.content?.value ?: return
        if (
            state.value.dialog != ExpenseFilter.EMPLOYMENT ||
                current.directory.items.none { it.id == event.id }
        )
            return
        updateState { it.copy(dialog = null) }
        select(current.selection.copy(employeeId = event.id, after = null))
    }

    private fun onEmploymentPageRequested(event: ExpensesEvent.EmploymentPageRequested) {
        val current = state.value.content?.value ?: return
        if (
            state.value.dialog != ExpenseFilter.EMPLOYMENT ||
                event.after == current.selection.employmentAfter ||
                (event.after != null && event.after != current.directory.nextCursor)
        )
            return
        select(
            current.selection.copy(employeeId = null, employmentAfter = event.after, after = null)
        )
    }

    private fun onPageRequested(event: ExpensesEvent.PageRequested) {
        val current = state.value.content?.value ?: return
        if (
            event.after == current.selection.after ||
                (event.after != null && event.after != current.page.nextCursor)
        )
            return
        select(current.selection.copy(after = event.after))
    }

    private fun select(selection: ExpenseSelection) {
        if (
            observation?.isActive != true ||
                state.value.readRestricted ||
                selection == state.value.filters
        )
            return
        updateState {
            it.copy(
                selection = selection,
                content = null,
                loading = true,
                cacheFailure = null,
                refreshFailure = it.refreshFailure?.takeIf(::expenseFailureInvalidatesCache),
            )
        }
    }

    private fun onClaimSelected(event: ExpensesEvent.ClaimSelected) {
        val current = state.value.content?.value ?: return
        if (state.value.readRestricted || current.page.items.none { it.id == event.id }) return
        viewModelScope.launch {
            emitEffect(ExpensesEffect.OpenClaim(event.id, current.selection.employmentAfter))
        }
    }

    private fun onCreateRequested(event: ExpensesEvent.CreateRequested) {
        if (!state.value.canCreate || state.value.refreshing) return
        val selection = state.value.content?.value?.selection ?: return
        val employee = selection.employeeId ?: return
        viewModelScope.launch {
            emitEffect(ExpensesEffect.OpenDraft(employee, selection.employmentAfter))
        }
    }

    private fun onBackRequested(event: ExpensesEvent.BackRequested) {
        viewModelScope.launch { emitEffect(ExpensesEffect.Back) }
    }

    private fun onVerificationRequested(event: ExpensesEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(ExpensesEffect.VerifyAccount) }
    }
}
