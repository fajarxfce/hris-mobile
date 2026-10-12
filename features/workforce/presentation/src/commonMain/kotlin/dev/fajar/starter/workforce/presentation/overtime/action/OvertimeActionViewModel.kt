package dev.fajar.starter.workforce.presentation.overtime.action

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import dev.fajar.starter.workforce.presentation.overtime.action.formatters.actualDateBounds
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class OvertimeActionViewModel(
    @InjectedParam private val target: OvertimeActionTarget,
    private val observeRequest: ObserveOvertimeRequest,
    private val refreshRequest: RefreshOvertimeRequest,
    private val observeCommand: ObserveOvertimeCommand,
    private val prepareAction: PrepareOvertimeAction,
    private val replayCommands: ReplayOvertimeCommands,
    private val reviewCommand: ReviewOvertimeCommand,
) :
    MviViewModel<OvertimeActionState, OvertimeActionEvent, OvertimeActionEffect>(
        OvertimeActionState(target)
    ) {
    private var foreground = false
    private var observation: Job? = null
    private var operation: Job? = null

    init {
        on<OvertimeActionEvent.Entered>(::onEntered)
        on<OvertimeActionEvent.Stopped>(::onStopped)
        on<OvertimeActionEvent.RefreshRequested>(::onRefreshRequested)
        on<OvertimeActionEvent.DatePickerRequested>(::onDatePickerRequested)
        on<OvertimeActionEvent.DateMonthRequested>(::onDateMonthRequested)
        on<OvertimeActionEvent.DateSelected>(::onDateSelected)
        on<OvertimeActionEvent.TimePickerRequested>(::onTimePickerRequested)
        on<OvertimeActionEvent.HoursChanged>(::onHoursChanged)
        on<OvertimeActionEvent.MinutesChanged>(::onMinutesChanged)
        on<OvertimeActionEvent.TimeConfirmed>(::onTimeConfirmed)
        on<OvertimeActionEvent.BreakChanged>(::onBreakChanged)
        on<OvertimeActionEvent.ReasonChanged>(::onReasonChanged)
        on<OvertimeActionEvent.SubmitRequested>(::onSubmitRequested)
        on<OvertimeActionEvent.RetryRequested>(::onRetryRequested)
        on<OvertimeActionEvent.OpenRequestRequested>(::onOpenRequestRequested)
        on<OvertimeActionEvent.DiscardRequested>(::onDiscardRequested)
        on<OvertimeActionEvent.DiscardConfirmed>(::onDiscardConfirmed)
        on<OvertimeActionEvent.DialogDismissed>(::onDialogDismissed)
        on<OvertimeActionEvent.BackRequested>(::onBackRequested)
        on<OvertimeActionEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: OvertimeActionEvent.Entered) {
        if (foreground) return
        foreground = true
        observeRetainedAction()
        onEvent(OvertimeActionEvent.RefreshRequested)
    }

    private fun onStopped(event: OvertimeActionEvent.Stopped) {
        foreground = false
        observation?.cancel()
        observation = null
        operation?.cancel()
        operation = null
        updateState {
            it.copy(
                content = null,
                command = null,
                commandReady = false,
                loading = false,
                work = null,
                dialog = null,
            )
        }
    }

    private fun observeRetainedAction() {
        observation?.cancel()
        if (!foreground || state.value.restriction != null) return
        observation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                combine(
                        observeRequest(target.workspaceId),
                        observeCommand(
                            target.workspaceId,
                            OvertimeCommandSlot.Request(target.selection.requestId),
                        ),
                    ) { request, command ->
                        request to command
                    }
                    .collect { (request, command) ->
                        ensureActive()
                        if (state.value.restriction != null) return@collect
                        updateState { it.withActionReads(request, command) }
                        state.value.restriction?.let(::reportFailure)
                    }
            }
        observation?.start()
    }

    private fun onRefreshRequested(event: OvertimeActionEvent.RefreshRequested) {
        if (!foreground || state.value.work != null) return
        updateState { it.copy(work = OvertimeActionWork.REFRESH, failure = null, dialog = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = refreshRequest(target.workspaceId, target.selection)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState { it.copy(restriction = null, failure = null) }
                            observeRetainedAction()
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null, loading = false) }
                }
            }
        operation?.start()
    }

    private fun onDatePickerRequested(event: OvertimeActionEvent.DatePickerRequested) {
        val actual = state.value.form?.actual ?: return
        if (!foreground || !state.value.editable) return
        val date =
            if (event.field == OvertimeActualField.START) actual.startsAt.date
            else actual.endsAt.date
        updateState {
            it.copy(
                dialog = OvertimeActionDialog.Date(event.field, LocalDate(date.year, date.month, 1))
            )
        }
    }

    private fun onDateMonthRequested(event: OvertimeActionEvent.DateMonthRequested) {
        val dialog = state.value.dialog as? OvertimeActionDialog.Date ?: return
        val bounds = state.value.actualDateBounds() ?: return
        if (!state.value.editable || event.offset !in setOf(-1, 1)) return
        val month = dialog.month.plus(event.offset, DateTimeUnit.MONTH)
        if (month > bounds.endInclusive || month.plus(1, DateTimeUnit.MONTH) <= bounds.start) return
        updateState { it.copy(dialog = dialog.copy(month = month)) }
    }

    private fun onDateSelected(event: OvertimeActionEvent.DateSelected) {
        val dialog = state.value.dialog as? OvertimeActionDialog.Date ?: return
        val form = state.value.form ?: return
        val actual = form.actual ?: return
        val bounds = state.value.actualDateBounds() ?: return
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
                    form.copy(
                        actual =
                            if (dialog.field == OvertimeActualField.START)
                                actual.copy(startsAt = LocalDateTime(date, actual.startsAt.time))
                            else actual.copy(endsAt = LocalDateTime(date, actual.endsAt.time))
                    ),
            )
        }
    }

    private fun onTimePickerRequested(event: OvertimeActionEvent.TimePickerRequested) {
        val actual = state.value.form?.actual ?: return
        if (!foreground || !state.value.editable) return
        val time =
            if (event.field == OvertimeActualField.START) actual.startsAt.time
            else actual.endsAt.time
        updateState {
            it.copy(
                dialog =
                    OvertimeActionDialog.Time(
                        event.field,
                        time.hour.toString().padStart(2, '0'),
                        time.minute.toString().padStart(2, '0'),
                    )
            )
        }
    }

    private fun onHoursChanged(event: OvertimeActionEvent.HoursChanged) {
        val dialog = state.value.dialog as? OvertimeActionDialog.Time ?: return
        if (state.value.editable && event.value.length <= 2 && event.value.all { it in '0'..'9' })
            updateState { it.copy(dialog = dialog.copy(hours = event.value)) }
    }

    private fun onMinutesChanged(event: OvertimeActionEvent.MinutesChanged) {
        val dialog = state.value.dialog as? OvertimeActionDialog.Time ?: return
        if (state.value.editable && event.value.length <= 2 && event.value.all { it in '0'..'9' })
            updateState { it.copy(dialog = dialog.copy(minutes = event.value)) }
    }

    private fun onTimeConfirmed(event: OvertimeActionEvent.TimeConfirmed) {
        val dialog = state.value.dialog as? OvertimeActionDialog.Time ?: return
        val time = dialog.value ?: return
        val form = state.value.form ?: return
        val actual = form.actual ?: return
        if (!state.value.editable) return
        updateState {
            it.copy(
                dialog = null,
                failure = null,
                form =
                    form.copy(
                        actual =
                            if (dialog.field == OvertimeActualField.START)
                                actual.copy(startsAt = LocalDateTime(actual.startsAt.date, time))
                            else actual.copy(endsAt = LocalDateTime(actual.endsAt.date, time))
                    ),
            )
        }
    }

    private fun onBreakChanged(event: OvertimeActionEvent.BreakChanged) {
        val form = state.value.form ?: return
        val actual = form.actual ?: return
        if (
            foreground &&
                state.value.editable &&
                event.value.length <= 3 &&
                event.value.all { it in '0'..'9' }
        )
            updateState {
                it.copy(
                    form = form.copy(actual = actual.copy(breakMinutes = event.value)),
                    failure = null,
                )
            }
    }

    private fun onReasonChanged(event: OvertimeActionEvent.ReasonChanged) {
        val form = state.value.form ?: return
        if (foreground && state.value.editable)
            updateState {
                it.copy(form = form.copy(reason = event.value.take(1_001)), failure = null)
            }
    }

    private fun onSubmitRequested(event: OvertimeActionEvent.SubmitRequested) {
        val current = state.value
        val snapshot = current.content ?: return
        val form = current.form ?: return
        if (!foreground || !current.canSubmit) return
        updateState { it.copy(work = OvertimeActionWork.SENDING, failure = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val prepared =
                        when (target.kind) {
                            OvertimeActionKind.ACTUAL ->
                                when (
                                    val interval =
                                        form.actual?.toIntervalInput()
                                            ?: overtimeFailure("client.overtime_time_required")
                                ) {
                                    is AppResult.Failed -> interval
                                    is AppResult.Success ->
                                        prepareAction(
                                            target.workspaceId,
                                            snapshot,
                                            interval.value,
                                            form.reason,
                                        )
                                }
                            OvertimeActionKind.WITHDRAW ->
                                prepareAction(
                                    target.workspaceId,
                                    snapshot,
                                    OvertimeRequestedAction.Withdraw,
                                    form.reason,
                                )
                        }
                    ensureActive()
                    when (prepared) {
                        is AppResult.Failed -> reportFailure(prepared.failure)
                        is AppResult.Success -> {
                            updateState {
                                it.withActionCommand(prepared.value).copy(commandReady = true)
                            }
                            deliverRetainedAction(prepared.value.command.id)
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
        operation?.start()
    }

    private fun onRetryRequested(event: OvertimeActionEvent.RetryRequested) {
        val record = state.value.command ?: return
        if (!foreground || !state.value.canRetry) return
        updateState { it.copy(work = OvertimeActionWork.SENDING, failure = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    deliverRetainedAction(record.command.id)
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
        operation?.start()
    }

    private suspend fun deliverRetainedAction(id: String) {
        val result = replayCommands(target.workspaceId, id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Failed -> reportFailure(result.failure)
            is AppResult.Success -> {
                updateState { it.withActionCommand(result.value).copy(commandReady = true) }
                when (val outcome = result.value.outcome) {
                    is OvertimeCommandOutcome.Confirmed -> reconcileAndReturn(result.value)
                    is OvertimeCommandOutcome.Unconfirmed -> outcome.failure?.let(::reportFailure)
                    is OvertimeCommandOutcome.Rejected -> reportFailure(outcome.failure)
                    OvertimeCommandOutcome.Pending -> Unit
                }
            }
        }
    }

    private fun onOpenRequestRequested(event: OvertimeActionEvent.OpenRequestRequested) {
        if (!foreground || state.value.restriction != null || state.value.work != null) return
        val record = state.value.command
        if (record == null) {
            onEvent(OvertimeActionEvent.BackRequested)
            return
        }
        if (record.outcome !is OvertimeCommandOutcome.Confirmed) return
        updateState { it.copy(work = OvertimeActionWork.OPENING, failure = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    reconcileAndReturn(record)
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
        operation?.start()
    }

    private suspend fun reconcileAndReturn(record: OvertimeCommandRecord) {
        val refreshed = refreshRequest(target.workspaceId, target.selection)
        currentCoroutineContext().ensureActive()
        if (refreshed is AppResult.Failed) {
            reportFailure(refreshed.failure)
            return
        }
        val reviewed = reviewCommand(target.workspaceId, record.command.id)
        currentCoroutineContext().ensureActive()
        when (reviewed) {
            is AppResult.Failed -> reportFailure(reviewed.failure)
            is AppResult.Success -> emitEffect(OvertimeActionEffect.Back)
        }
    }

    private fun onDiscardRequested(event: OvertimeActionEvent.DiscardRequested) {
        if (foreground && state.value.canDiscard)
            updateState { it.copy(dialog = OvertimeActionDialog.Discard) }
    }

    private fun onDiscardConfirmed(event: OvertimeActionEvent.DiscardConfirmed) {
        val record = state.value.command ?: return
        if (
            !foreground ||
                !state.value.canDiscard ||
                state.value.dialog != OvertimeActionDialog.Discard
        )
            return
        updateState { it.copy(work = OvertimeActionWork.DISCARDING, failure = null, dialog = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = reviewCommand(target.workspaceId, record.command.id)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState { it.copy(command = null, commandReady = false) }
                            observeRetainedAction()
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
        operation?.start()
    }

    private fun reportFailure(failure: Failure) {
        updateState { it.withActionFailure(failure) }
        if (overtimeFailureInvalidatesCache(failure)) {
            observation?.cancel()
            observation = null
            operation?.cancel()
            operation = null
            if (failure.code in workspaceAccessLossCodes && !state.value.needsVerification)
                viewModelScope.launch { emitEffect(OvertimeActionEffect.RecheckWorkspace) }
        }
    }

    private fun onDialogDismissed(event: OvertimeActionEvent.DialogDismissed) {
        updateState { it.copy(dialog = null) }
    }

    private fun onBackRequested(event: OvertimeActionEvent.BackRequested) {
        if (!foreground) return
        if (state.value.dialog != null) onEvent(OvertimeActionEvent.DialogDismissed)
        else {
            onEvent(OvertimeActionEvent.Stopped)
            viewModelScope.launch { emitEffect(OvertimeActionEffect.Back) }
        }
    }

    private fun onVerificationRequested(event: OvertimeActionEvent.VerificationRequested) {
        if (foreground && state.value.needsVerification)
            viewModelScope.launch { emitEffect(OvertimeActionEffect.VerifyAccount) }
    }
}
