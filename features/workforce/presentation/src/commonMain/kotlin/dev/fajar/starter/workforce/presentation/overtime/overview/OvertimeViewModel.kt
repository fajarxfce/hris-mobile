package dev.fajar.starter.workforce.presentation.overtime.overview

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import dev.fajar.starter.workforce.presentation.overtime.verificationCodes
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class OvertimeViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveOvertime,
    private val refresh: RefreshOvertime,
) : MviViewModel<OvertimeState, OvertimeEvent, OvertimeEffect>(OvertimeState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null

    init {
        on<OvertimeEvent.Entered>(::onEntered)
        on<OvertimeEvent.Stopped>(::onStopped)
        on<OvertimeEvent.RefreshRequested>(::onRefreshRequested)
        on<OvertimeEvent.PlanRequested>(::onPlanRequested)
        on<OvertimeEvent.BackRequested>(::onBackRequested)
        on<OvertimeEvent.VerificationRequested>(::onVerificationRequested)
        on<OvertimeEvent.MonthFilterRequested>(::onMonthFilterRequested)
        on<OvertimeEvent.MonthRequested>(::onMonthRequested)
        on<OvertimeEvent.PickerYearRequested>(::onPickerYearRequested)
        on<OvertimeEvent.PickerMonthSelected>(::onPickerMonthSelected)
        on<OvertimeEvent.StatusFilterRequested>(::onStatusFilterRequested)
        on<OvertimeEvent.StatusSelected>(::onStatusSelected)
        on<OvertimeEvent.EmploymentFilterRequested>(::onEmploymentFilterRequested)
        on<OvertimeEvent.EmploymentSelected>(::onEmploymentSelected)
        on<OvertimeEvent.EmploymentPageRequested>(::onEmploymentPageRequested)
        on<OvertimeEvent.RequestsPageRequested>(::onRequestsPageRequested)
        on<OvertimeEvent.DialogDismissed>(::onDialogDismissed)
        on<OvertimeEvent.RequestSelected>(::onRequestSelected)
    }

    private fun onEntered(event: OvertimeEvent.Entered) {
        if (observation?.isActive == true) return
        updateState { it.copy(loading = true) }
        observeSelection()
        onEvent(OvertimeEvent.RefreshRequested)
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
                        if (selection == state.value.selection && !state.value.readRestricted)
                            updateState { it.withCachedOvertime(result) }
                    }
            }
    }

    private fun onStopped(event: OvertimeEvent.Stopped) {
        observation?.cancel()
        observation = null
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                selection = it.filters,
                content = null,
                dialog = null,
                pickerYear = null,
                loading = false,
                refreshing = false,
            )
        }
    }

    private fun onRefreshRequested(event: OvertimeEvent.RefreshRequested) {
        if (observation?.isActive != true || acquisition?.isActive == true) return
        val requested = state.value.selection ?: OvertimeSelection()
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
                val result = refresh(workspaceId, requested)
                ensureActive()
                if (result is AppResult.Success) observation?.cancel()
                updateState { it.withOvertimeRefresh(result, requested) }
                if (result is AppResult.Success) observeSelection()
                if (
                    result is AppResult.Failed &&
                        result.failure.code in workspaceAccessLossCodes &&
                        result.failure.code !in verificationCodes
                )
                    emitEffect(OvertimeEffect.RecheckWorkspace)
            }
    }

    private fun onMonthFilterRequested(event: OvertimeEvent.MonthFilterRequested) {
        val month = state.value.filters?.period?.from ?: return
        if (!state.value.readRestricted)
            updateState { it.copy(dialog = OvertimeDialog.MONTH, pickerYear = month.year) }
    }

    private fun onMonthRequested(event: OvertimeEvent.MonthRequested) {
        val month = state.value.filters?.period?.from ?: return
        if (
            event.month.day != 1 ||
                event.month.year !in 2000..2100 ||
                event.month !in
                    setOf(month.minus(1, DateTimeUnit.MONTH), month.plus(1, DateTimeUnit.MONTH))
        )
            return
        selectMonth(event.month)
    }

    private fun onPickerYearRequested(event: OvertimeEvent.PickerYearRequested) {
        val year = state.value.pickerYear ?: return
        if (
            state.value.dialog == OvertimeDialog.MONTH &&
                event.year in 2000..2100 &&
                event.year in setOf(year - 1, year + 1)
        )
            updateState { it.copy(pickerYear = event.year) }
    }

    private fun onPickerMonthSelected(event: OvertimeEvent.PickerMonthSelected) {
        val year = state.value.pickerYear ?: return
        if (state.value.dialog != OvertimeDialog.MONTH || event.month !in 1..12) return
        selectMonth(LocalDate(year, event.month, 1))
    }

    private fun selectMonth(month: LocalDate) {
        val selection = state.value.filters ?: return
        updateState { it.copy(dialog = null, pickerYear = null) }
        if (selection.period?.from != month)
            changeSelection(selection.copy(period = overtimeMonth(month), requestAfter = null))
    }

    private fun onStatusFilterRequested(event: OvertimeEvent.StatusFilterRequested) {
        if (state.value.filters?.employeeId != null && !state.value.readRestricted)
            updateState { it.copy(dialog = OvertimeDialog.STATUS) }
    }

    private fun onStatusSelected(event: OvertimeEvent.StatusSelected) {
        val selection = state.value.filters ?: return
        if (state.value.dialog != OvertimeDialog.STATUS) return
        updateState { it.copy(dialog = null) }
        if (selection.status != event.status)
            changeSelection(selection.copy(status = event.status, requestAfter = null))
    }

    private fun onEmploymentFilterRequested(event: OvertimeEvent.EmploymentFilterRequested) {
        if (state.value.content != null && !state.value.readRestricted)
            updateState { it.copy(dialog = OvertimeDialog.EMPLOYMENT) }
    }

    private fun onEmploymentSelected(event: OvertimeEvent.EmploymentSelected) {
        val content = state.value.content?.value ?: return
        if (
            state.value.dialog != OvertimeDialog.EMPLOYMENT ||
                content.directory.items.none { it.id == event.id }
        )
            return
        updateState { it.copy(dialog = null) }
        if (content.selection.employeeId != event.id)
            changeSelection(content.selection.copy(employeeId = event.id, requestAfter = null))
    }

    private fun onEmploymentPageRequested(event: OvertimeEvent.EmploymentPageRequested) {
        val content = state.value.content?.value ?: return
        if (
            state.value.dialog != OvertimeDialog.EMPLOYMENT ||
                event.after == content.selection.employmentAfter ||
                (event.after != null && event.after != content.directory.nextCursor)
        )
            return
        changeSelection(
            content.selection.copy(
                employeeId = null,
                employmentAfter = event.after,
                requestAfter = null,
            ),
            OvertimeDialog.EMPLOYMENT,
        )
    }

    private fun onRequestsPageRequested(event: OvertimeEvent.RequestsPageRequested) {
        val content = state.value.content?.value ?: return
        if (
            event.after == content.selection.requestAfter ||
                (event.after != null && event.after != content.requests.nextCursor)
        )
            return
        changeSelection(content.selection.copy(requestAfter = event.after))
    }

    private fun changeSelection(selection: OvertimeSelection, dialog: OvertimeDialog? = null) {
        if (observation?.isActive != true || state.value.readRestricted) return
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                selection = selection,
                content = null,
                dialog = dialog,
                pickerYear = null,
                loading = true,
                refreshing = false,
                cacheFailure = it.cacheFailure?.takeIf(::overtimeFailureInvalidatesCache),
                refreshFailure = it.refreshFailure?.takeIf(::overtimeFailureInvalidatesCache),
            )
        }
        onEvent(OvertimeEvent.RefreshRequested)
    }

    private fun onDialogDismissed(event: OvertimeEvent.DialogDismissed) {
        updateState { it.copy(dialog = null, pickerYear = null) }
    }

    private fun onRequestSelected(event: OvertimeEvent.RequestSelected) {
        val content = state.value.content?.value ?: return
        val request = content.requests.items.find { it.id == event.id } ?: return
        if (state.value.readRestricted) return
        viewModelScope.launch {
            emitEffect(
                OvertimeEffect.OpenRequest(
                    OvertimeRequestSelection(
                        request.id,
                        request.employeeId,
                        content.selection.employmentAfter,
                    )
                )
            )
        }
    }

    private fun onBackRequested(event: OvertimeEvent.BackRequested) {
        viewModelScope.launch { emitEffect(OvertimeEffect.Back) }
    }

    private fun onPlanRequested(event: OvertimeEvent.PlanRequested) {
        if (!state.value.canPlan) return
        val content = state.value.content?.value ?: return
        val employeeId = content.selection.employeeId ?: return
        viewModelScope.launch {
            emitEffect(
                OvertimeEffect.PlanRequest(
                    OvertimePlanningSelection(
                        employeeId,
                        content.directory.asOf,
                        content.selection.employmentAfter,
                    )
                )
            )
        }
    }

    private fun onVerificationRequested(event: OvertimeEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(OvertimeEffect.VerifyAccount) }
    }
}
