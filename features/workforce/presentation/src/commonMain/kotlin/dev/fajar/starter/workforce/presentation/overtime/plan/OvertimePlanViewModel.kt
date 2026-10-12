package dev.fajar.starter.workforce.presentation.overtime.plan

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailureInvalidatesCache
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import dev.fajar.starter.workforce.presentation.overtime.plan.formatters.planDateBounds
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class OvertimePlanViewModel(
    @InjectedParam private val target: OvertimePlanTarget,
    @InjectedParam private val clock: Clock,
    private val reviewSchedule: ReviewOvertimeSchedule,
    private val observeCommand: ObserveOvertimeCommand,
    private val preparePlan: PrepareOvertimePlan,
    private val replayCommands: ReplayOvertimeCommands,
    private val reviewCommand: ReviewOvertimeCommand,
    private val refreshRequest: RefreshOvertimeRequest,
) :
    MviViewModel<OvertimePlanState, OvertimePlanEvent, OvertimePlanEffect>(
        initialOvertimePlanState(target)
    ) {
    private var foreground = false
    private var observation: Job? = null
    private var operation: Job? = null
    private var expiration: Job? = null

    init {
        on<OvertimePlanEvent.Entered>(::onEntered)
        on<OvertimePlanEvent.Stopped>(::onStopped)
        on<OvertimePlanEvent.ReloadRequested>(::onReloadRequested)
        on<OvertimePlanEvent.DatePickerRequested>(::onDatePickerRequested)
        on<OvertimePlanEvent.DateMonthRequested>(::onDateMonthRequested)
        on<OvertimePlanEvent.DateSelected>(::onDateSelected)
        on<OvertimePlanEvent.TimePickerRequested>(::onTimePickerRequested)
        on<OvertimePlanEvent.HoursChanged>(::onHoursChanged)
        on<OvertimePlanEvent.MinutesChanged>(::onMinutesChanged)
        on<OvertimePlanEvent.TimeConfirmed>(::onTimeConfirmed)
        on<OvertimePlanEvent.BreakChanged>(::onBreakChanged)
        on<OvertimePlanEvent.ReasonChanged>(::onReasonChanged)
        on<OvertimePlanEvent.ReviewExpired>(::onReviewExpired)
        on<OvertimePlanEvent.SubmitRequested>(::onSubmitRequested)
        on<OvertimePlanEvent.RetryRequested>(::onRetryRequested)
        on<OvertimePlanEvent.OpenRequestRequested>(::onOpenRequestRequested)
        on<OvertimePlanEvent.DiscardRequested>(::onDiscardRequested)
        on<OvertimePlanEvent.DiscardConfirmed>(::onDiscardConfirmed)
        on<OvertimePlanEvent.DialogDismissed>(::onDialogDismissed)
        on<OvertimePlanEvent.BackRequested>(::onBackRequested)
        on<OvertimePlanEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: OvertimePlanEvent.Entered) {
        if (foreground || state.value.form == null) return
        foreground = true
        if (state.value.restriction != null) onEvent(OvertimePlanEvent.ReloadRequested)
        else observeRetainedPlan()
    }

    private fun onStopped(event: OvertimePlanEvent.Stopped) {
        foreground = false
        observation?.cancel()
        observation = null
        operation?.cancel()
        operation = null
        expiration?.cancel()
        expiration = null
        updateState {
            it.copy(
                prepared = null,
                command = null,
                commandReady = false,
                work = null,
                dialog = null,
            )
        }
    }

    private fun observeRetainedPlan() {
        observation?.cancel()
        if (!foreground || state.value.restriction != null) return
        observation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                observeCommand(target.workspaceId, OvertimeCommandSlot.Plan(target.employeeId))
                    .collect { result ->
                        ensureActive()
                        if (state.value.restriction != null) return@collect
                        when (result) {
                            is AppResult.Failed -> {
                                updateState { it.copy(command = null, commandReady = false) }
                                reportFailure(result.failure)
                            }
                            is AppResult.Success -> {
                                val initial = !state.value.commandReady
                                if (result.value != null) expiration?.cancel()
                                updateState { it.withPlanCommand(result.value) }
                                if (
                                    initial &&
                                        result.value == null &&
                                        state.value.prepared == null &&
                                        state.value.work == null
                                )
                                    onEvent(OvertimePlanEvent.ReloadRequested)
                            }
                        }
                    }
            }
        observation?.start()
    }

    private fun onReloadRequested(event: OvertimePlanEvent.ReloadRequested) {
        if (
            !foreground ||
                state.value.work in
                    setOf(
                        OvertimePlanWork.SENDING,
                        OvertimePlanWork.OPENING,
                        OvertimePlanWork.DISCARDING,
                    )
        )
            return
        val form = state.value.form ?: return
        operation?.cancel()
        expiration?.cancel()
        updateState {
            it.copy(
                work = OvertimePlanWork.REVIEW,
                prepared = null,
                reviewExpired = false,
                failure = null,
                dialog = null,
            )
        }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result =
                        reviewSchedule(
                            target.workspaceId,
                            OvertimePlanningSelection(
                                target.employeeId,
                                form.workDate,
                                target.employmentAfter,
                            ),
                        )
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState { current ->
                                current.copy(
                                    prepared = result.value.takeIf { current.command == null },
                                    restriction = null,
                                    failure = null,
                                )
                            }
                            if (state.value.command == null)
                                expiration =
                                    viewModelScope.launch {
                                        delay(result.value.expiresAt - clock.now())
                                        onEvent(OvertimePlanEvent.ReviewExpired(result.value))
                                    }
                            observeRetainedPlan()
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
        operation?.start()
    }

    private fun onDatePickerRequested(event: OvertimePlanEvent.DatePickerRequested) {
        val form = state.value.form ?: return
        if (!foreground || !state.value.editable) return
        val date =
            when (event.field) {
                OvertimePlanDateField.WORK -> form.workDate
                OvertimePlanDateField.START -> form.startDate
                OvertimePlanDateField.END -> form.endDate
            }
        updateState {
            it.copy(
                dialog = OvertimePlanDialog.Date(event.field, LocalDate(date.year, date.month, 1))
            )
        }
    }

    private fun onDateMonthRequested(event: OvertimePlanEvent.DateMonthRequested) {
        val dialog = state.value.dialog as? OvertimePlanDialog.Date ?: return
        val bounds = state.value.planDateBounds(dialog.field) ?: return
        if (!state.value.editable || event.offset !in setOf(-1, 1)) return
        val month = dialog.month.plus(event.offset, DateTimeUnit.MONTH)
        if (month > bounds.endInclusive || month.plus(1, DateTimeUnit.MONTH) <= bounds.start) return
        updateState { it.copy(dialog = dialog.copy(month = month)) }
    }

    private fun onDateSelected(event: OvertimePlanEvent.DateSelected) {
        val dialog = state.value.dialog as? OvertimePlanDialog.Date ?: return
        val form = state.value.form ?: return
        val bounds = state.value.planDateBounds(dialog.field) ?: return
        if (
            !state.value.editable ||
                event.day !in 1..dialog.month.daysUntil(dialog.month.plus(1, DateTimeUnit.MONTH))
        )
            return
        val date = LocalDate(dialog.month.year, dialog.month.month, event.day)
        if (date !in bounds) return
        updateState {
            it.copy(
                dialog = null,
                failure = null,
                form =
                    when (dialog.field) {
                        OvertimePlanDateField.WORK ->
                            if (date == form.workDate) form
                            else OvertimePlanForm(date, reason = form.reason)
                        OvertimePlanDateField.START -> form.copy(startDate = date)
                        OvertimePlanDateField.END -> form.copy(endDate = date)
                    },
            )
        }
        if (dialog.field == OvertimePlanDateField.WORK && date != form.workDate)
            onEvent(OvertimePlanEvent.ReloadRequested)
    }

    private fun onTimePickerRequested(event: OvertimePlanEvent.TimePickerRequested) {
        val form = state.value.form ?: return
        if (!foreground || !state.value.editable) return
        val time = if (event.field == OvertimeTimeField.START) form.startsAt else form.endsAt
        updateState {
            it.copy(
                dialog =
                    OvertimePlanDialog.Time(
                        event.field,
                        time?.hour?.toString()?.padStart(2, '0').orEmpty(),
                        time?.minute?.toString()?.padStart(2, '0') ?: "00",
                    )
            )
        }
    }

    private fun onHoursChanged(event: OvertimePlanEvent.HoursChanged) {
        val dialog = state.value.dialog as? OvertimePlanDialog.Time ?: return
        if (state.value.editable && event.value.length <= 2 && event.value.all { it in '0'..'9' })
            updateState { it.copy(dialog = dialog.copy(hours = event.value)) }
    }

    private fun onMinutesChanged(event: OvertimePlanEvent.MinutesChanged) {
        val dialog = state.value.dialog as? OvertimePlanDialog.Time ?: return
        if (state.value.editable && event.value.length <= 2 && event.value.all { it in '0'..'9' })
            updateState { it.copy(dialog = dialog.copy(minutes = event.value)) }
    }

    private fun onTimeConfirmed(event: OvertimePlanEvent.TimeConfirmed) {
        val dialog = state.value.dialog as? OvertimePlanDialog.Time ?: return
        val value = dialog.value ?: return
        val form = state.value.form ?: return
        if (!state.value.editable) return
        updateState {
            it.copy(
                dialog = null,
                failure = null,
                form =
                    if (dialog.field == OvertimeTimeField.START) form.copy(startsAt = value)
                    else form.copy(endsAt = value),
            )
        }
    }

    private fun onBreakChanged(event: OvertimePlanEvent.BreakChanged) {
        val form = state.value.form ?: return
        if (
            foreground &&
                state.value.editable &&
                event.value.length <= 3 &&
                event.value.all { it in '0'..'9' }
        )
            updateState { it.copy(form = form.copy(breakMinutes = event.value), failure = null) }
    }

    private fun onReasonChanged(event: OvertimePlanEvent.ReasonChanged) {
        val form = state.value.form ?: return
        if (foreground && state.value.editable)
            updateState {
                it.copy(form = form.copy(reason = event.value.take(1_001)), failure = null)
            }
    }

    private fun onReviewExpired(event: OvertimePlanEvent.ReviewExpired) {
        if (state.value.prepared == event.prepared) updateState { it.copy(reviewExpired = true) }
    }

    private fun onSubmitRequested(event: OvertimePlanEvent.SubmitRequested) {
        if (!foreground || !state.value.canSubmit) return
        val prepared = state.value.prepared ?: return
        val form = state.value.form ?: return
        val input =
            when (val result = form.toIntervalInput()) {
                is AppResult.Failed -> {
                    reportFailure(result.failure)
                    return
                }
                is AppResult.Success -> result.value
            }
        updateState { it.copy(work = OvertimePlanWork.SENDING, failure = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = preparePlan(target.workspaceId, prepared, input, form.reason)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            expiration?.cancel()
                            updateState { it.withPlanCommand(result.value) }
                            deliverRetainedPlan(result.value.command.id)
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
        operation?.start()
    }

    private fun onRetryRequested(event: OvertimePlanEvent.RetryRequested) {
        val record = state.value.command ?: return
        if (!foreground || !state.value.canRetry) return
        updateState { it.copy(work = OvertimePlanWork.SENDING, failure = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    deliverRetainedPlan(record.command.id)
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
        operation?.start()
    }

    private suspend fun deliverRetainedPlan(id: String) {
        val result = replayCommands(target.workspaceId, id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Failed -> reportFailure(result.failure)
            is AppResult.Success -> {
                updateState { it.withPlanCommand(result.value) }
                when (val outcome = result.value.outcome) {
                    is OvertimeCommandOutcome.Confirmed -> reconcileAndOpen(result.value)
                    is OvertimeCommandOutcome.Unconfirmed -> outcome.failure?.let(::reportFailure)
                    is OvertimeCommandOutcome.Rejected -> reportFailure(outcome.failure)
                    OvertimeCommandOutcome.Pending -> Unit
                }
            }
        }
    }

    private fun onOpenRequestRequested(event: OvertimePlanEvent.OpenRequestRequested) {
        val record = state.value.command ?: return
        if (
            !foreground ||
                state.value.restriction != null ||
                state.value.work != null ||
                record.outcome !is OvertimeCommandOutcome.Confirmed
        )
            return
        updateState { it.copy(work = OvertimePlanWork.OPENING, failure = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    reconcileAndOpen(record)
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
        operation?.start()
    }

    private suspend fun reconcileAndOpen(record: OvertimeCommandRecord) {
        val selection =
            OvertimeRequestSelection(
                record.command.requestId,
                record.command.employeeId,
                record.command.employmentAfter,
            )
        val refreshed = refreshRequest(target.workspaceId, selection)
        currentCoroutineContext().ensureActive()
        if (refreshed is AppResult.Failed) {
            reportFailure(refreshed.failure)
            return
        }
        val reviewed = reviewCommand(target.workspaceId, record.command.id)
        currentCoroutineContext().ensureActive()
        when (reviewed) {
            is AppResult.Failed -> reportFailure(reviewed.failure)
            is AppResult.Success -> emitEffect(OvertimePlanEffect.OpenRequest(selection))
        }
    }

    private fun onDiscardRequested(event: OvertimePlanEvent.DiscardRequested) {
        if (foreground && state.value.canDiscard)
            updateState { it.copy(dialog = OvertimePlanDialog.Discard) }
    }

    private fun onDiscardConfirmed(event: OvertimePlanEvent.DiscardConfirmed) {
        val record = state.value.command ?: return
        if (
            !foreground ||
                !state.value.canDiscard ||
                state.value.dialog != OvertimePlanDialog.Discard
        )
            return
        updateState { it.copy(work = OvertimePlanWork.DISCARDING, failure = null, dialog = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = reviewCommand(target.workspaceId, record.command.id)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            if (result.value)
                                updateState {
                                    it.copy(command = null, commandReady = false, prepared = null)
                                }
                            observeRetainedPlan()
                        }
                    }
                } finally {
                    if (isActive) {
                        updateState { it.copy(work = null) }
                        if (state.value.command == null && state.value.restriction == null)
                            onEvent(OvertimePlanEvent.ReloadRequested)
                    }
                }
            }
        operation?.start()
    }

    private fun reportFailure(failure: Failure) {
        updateState { it.withPlanFailure(failure) }
        if (overtimeFailureInvalidatesCache(failure)) {
            observation?.cancel()
            observation = null
            expiration?.cancel()
            expiration = null
            operation?.cancel()
            operation = null
            if (failure.code in workspaceAccessLossCodes && !state.value.needsVerification)
                viewModelScope.launch { emitEffect(OvertimePlanEffect.RecheckWorkspace) }
        }
    }

    private fun onDialogDismissed(event: OvertimePlanEvent.DialogDismissed) {
        updateState { it.copy(dialog = null) }
    }

    private fun onBackRequested(event: OvertimePlanEvent.BackRequested) {
        if (state.value.dialog != null) onEvent(OvertimePlanEvent.DialogDismissed)
        else {
            onEvent(OvertimePlanEvent.Stopped)
            viewModelScope.launch { emitEffect(OvertimePlanEffect.Back) }
        }
    }

    private fun onVerificationRequested(event: OvertimePlanEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(OvertimePlanEffect.VerifyAccount) }
    }
}
