package dev.fajar.starter.workforce.presentation.overtime.action

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import dev.fajar.starter.workforce.domain.overtime.working.usecases.*
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
    private val refreshRequest: LoadOvertimeRequest,
    private val observeCommand: ObserveOvertimeCommand,
    private val prepareAction: PrepareOvertimeAction,
    private val replayCommands: ReplayOvertimeCommands,
    private val reviewCommand: ReviewOvertimeCommand,
    private val synchronize: SyncOvertime,
    private val openWorkingCopy: OpenOvertimeActionWorkingCopy,
    private val saveWorkingCopy: SaveOvertimeActionWorkingCopy,
    private val discardWorkingCopy: DiscardOvertimeActionWorkingCopy,
) :
    MviViewModel<OvertimeActionState, OvertimeActionEvent, OvertimeActionEffect>(
        OvertimeActionState(target)
    ) {
    private var foreground = false
    private var observation: Job? = null
    private var operation: Job? = null
    private var localSave: Job? = null

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
        on<OvertimeActionEvent.SaveRetryRequested>(::onSaveRetryRequested)
        on<OvertimeActionEvent.ReloadDraftRequested>(::onReloadDraftRequested)
        on<OvertimeActionEvent.ReloadDraftConfirmed>(::onReloadDraftConfirmed)
        on<OvertimeActionEvent.DiscardDraftRequested>(::onDiscardDraftRequested)
        on<OvertimeActionEvent.DiscardDraftConfirmed>(::onDiscardDraftConfirmed)
        on<OvertimeActionEvent.ExitWithoutSaving>(::onExitWithoutSaving)
    }

    private fun onEntered(event: OvertimeActionEvent.Entered) {
        if (foreground || state.value.closing) return
        foreground = true
        observeRetainedAction()
        onEvent(OvertimeActionEvent.RefreshRequested)
    }

    private fun onStopped(event: OvertimeActionEvent.Stopped) {
        persistEdits(immediate = true)
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
                        observeRequest(target.workspaceId, target.selection),
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
                        val next = state.value.withActionReads(request, command)
                        val restriction = next.restriction
                        if (restriction != null) reportFailure(restriction)
                        else updateState { next }
                    }
            }
        observation?.start()
    }

    private fun onRefreshRequested(event: OvertimeActionEvent.RefreshRequested) {
        if (!foreground || state.value.work != null || state.value.closing) return
        refreshAction()
    }

    private fun refreshAction(reloadDraft: Boolean = false) {
        operation?.cancel()
        if (reloadDraft && state.value.persistence?.writing != true) localSave?.cancel()
        updateState {
            it.copy(
                work =
                    if (reloadDraft || it.persistence == null) OvertimeActionWork.RESTORING
                    else OvertimeActionWork.REFRESH,
                failure = null,
                dialog = null,
            )
        }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (reloadDraft) {
                        localSave?.join()
                        ensureActive()
                        updateState { it.copy(persistence = null) }
                    }
                    val recovery = synchronize.recoverAccess(target.workspaceId)
                    ensureActive()
                    if (recovery is AppResult.Failed) {
                        reportFailure(recovery.failure)
                        return@launch
                    }
                    var acquisition: AppResult<Unit>? = null
                    val local =
                        state.value.persistence
                            ?: run {
                                var result =
                                    openWorkingCopy(
                                        target.workspaceId,
                                        target.selection,
                                        target.kind.action,
                                    )
                                ensureActive()
                                if (
                                    result is AppResult.Failed &&
                                        result.failure.code ==
                                            "client.overtime_working_copy_unavailable"
                                ) {
                                    acquisition =
                                        refreshRequest(target.workspaceId, target.selection)
                                    ensureActive()
                                    result =
                                        when (val fetched = requireNotNull(acquisition)) {
                                            is AppResult.Failed -> fetched
                                            is AppResult.Success ->
                                                openWorkingCopy(
                                                    target.workspaceId,
                                                    target.selection,
                                                    target.kind.action,
                                                )
                                        }
                                }
                                ensureActive()
                                when (result) {
                                    is AppResult.Failed -> {
                                        reportFailure(result.failure)
                                        return@launch
                                    }
                                    is AppResult.Success ->
                                        OvertimeActionEditPersistence(result.value)
                                }
                            }
                    updateState {
                        it.withRestoredActionDraft(local).copy(work = OvertimeActionWork.REFRESH)
                    }
                    val result = acquisition ?: refreshRequest(target.workspaceId, target.selection)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState {
                                it.copy(restriction = null, failure = null)
                                    .withRestoredActionDraft(local)
                            }
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
        stageEdits()
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
        stageEdits()
    }

    private fun onBreakChanged(event: OvertimeActionEvent.BreakChanged) {
        val form = state.value.form ?: return
        val actual = form.actual ?: return
        if (
            !foreground ||
                !state.value.editable ||
                event.value.length > 3 ||
                event.value.any { it !in '0'..'9' }
        )
            return
        updateState {
            it.copy(
                form = form.copy(actual = actual.copy(breakMinutes = event.value)),
                failure = null,
            )
        }
        stageEdits()
    }

    private fun onReasonChanged(event: OvertimeActionEvent.ReasonChanged) {
        val form = state.value.form ?: return
        if (!foreground || !state.value.editable) return
        updateState { it.copy(form = form.copy(reason = event.value.take(1_001)), failure = null) }
        stageEdits()
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
                    if (!flushLocalEdits()) return@launch
                    val revision = state.value.persistence?.stored?.revision ?: return@launch
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
                                            revision,
                                        )
                                }
                            OvertimeActionKind.WITHDRAW ->
                                prepareAction(
                                    target.workspaceId,
                                    snapshot,
                                    OvertimeRequestedAction.Withdraw,
                                    form.reason,
                                    revision,
                                )
                        }
                    ensureActive()
                    when (prepared) {
                        is AppResult.Failed ->
                            if (prepared.failure.code == "client.overtime_working_copy_changed")
                                reportLocalFailure(prepared.failure)
                            else reportFailure(prepared.failure)
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
            is AppResult.Success -> {
                updateState { it.copy(closing = true, persistence = null) }
                emitEffect(OvertimeActionEffect.Back)
            }
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
        val previousCode = state.value.restriction?.code
        updateState { it.withActionFailure(failure) }
        if (overtimeFailureInvalidatesCache(failure)) {
            localSave?.cancel()
            localSave = null
            observation?.cancel()
            observation = null
            // Shared admission observation must not cancel the write that produced it.
            if (
                failure.code != previousCode &&
                    failure.code in workspaceAccessLossCodes &&
                    !state.value.needsVerification
            )
                viewModelScope.launch { emitEffect(OvertimeActionEffect.RecheckWorkspace) }
        }
    }

    private fun stageEdits() {
        val form = state.value.form ?: return
        if (state.value.restriction != null || state.value.command != null) return
        updateState { it.copy(persistence = it.persistence?.edit(form)) }
        persistEdits()
    }

    /** Coalesce waiting input; never cancel an admitted write to start a newer one. */
    private fun persistEdits(immediate: Boolean = false) {
        val local = state.value.persistence ?: return
        if (
            state.value.closing ||
                state.value.restriction != null ||
                local.saved ||
                local.failure != null
        )
            return
        if (localSave?.isActive == true) {
            if (!immediate || local.writing) return
            localSave?.cancel()
        }
        localSave =
            viewModelScope.launch {
                if (!immediate) delay(250)
                while (isActive) {
                    val current = state.value.persistence ?: return@launch
                    if (
                        current.saved ||
                            current.failure != null ||
                            state.value.restriction != null ||
                            state.value.closing ||
                            state.value.command != null
                    )
                        return@launch
                    val mutation =
                        current.pending
                            ?: OvertimeActionWorkingMutation(current.stored, current.edited)
                    updateState {
                        it.copy(
                            persistence = it.persistence?.copy(pending = mutation, writing = true)
                        )
                    }
                    val result =
                        saveWorkingCopy(
                            target.workspaceId,
                            target.selection,
                            mutation.previous,
                            mutation.updated,
                        )
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> {
                            reportLocalFailure(result.failure)
                            return@launch
                        }
                        is AppResult.Success ->
                            updateState {
                                it.copy(persistence = it.persistence?.acknowledge(result.value))
                            }
                    }
                }
            }
    }

    private suspend fun flushLocalEdits(): Boolean {
        persistEdits(immediate = true)
        localSave?.join()
        currentCoroutineContext().ensureActive()
        return state.value.persistence?.saved == true && state.value.restriction == null
    }

    private fun reportLocalFailure(failure: Failure) {
        updateState {
            it.copy(persistence = it.persistence?.copy(writing = false, failure = failure))
        }
        if (overtimeFailureInvalidatesCache(failure)) reportFailure(failure)
    }

    private fun onSaveRetryRequested(event: OvertimeActionEvent.SaveRetryRequested) {
        val local = state.value.persistence ?: return
        if (
            !foreground ||
                state.value.closing ||
                state.value.restriction != null ||
                state.value.work in transactionWork ||
                local.writing ||
                local.failure == null ||
                local.failure.code == "client.overtime_working_copy_changed"
        )
            return
        updateState { it.copy(persistence = local.copy(failure = null)) }
        persistEdits(immediate = true)
    }

    private fun onReloadDraftRequested(event: OvertimeActionEvent.ReloadDraftRequested) {
        if (
            foreground &&
                !state.value.closing &&
                state.value.work == null &&
                state.value.restriction == null &&
                state.value.persistence != null &&
                state.value.command == null
        )
            updateState { it.copy(dialog = OvertimeActionDialog.ReloadDraft) }
    }

    private fun onReloadDraftConfirmed(event: OvertimeActionEvent.ReloadDraftConfirmed) {
        if (
            foreground &&
                state.value.dialog == OvertimeActionDialog.ReloadDraft &&
                state.value.work == null
        )
            refreshAction(reloadDraft = true)
    }

    private fun onDiscardDraftRequested(event: OvertimeActionEvent.DiscardDraftRequested) {
        if (foreground && state.value.canManageDraft)
            updateState { it.copy(dialog = OvertimeActionDialog.DiscardDraft) }
    }

    private fun onDiscardDraftConfirmed(event: OvertimeActionEvent.DiscardDraftConfirmed) {
        if (
            !foreground ||
                state.value.dialog != OvertimeActionDialog.DiscardDraft ||
                state.value.work != null ||
                !state.value.canManageDraft
        )
            return
        updateState { it.copy(dialog = null, work = OvertimeActionWork.DISCARDING) }
        operation =
            viewModelScope.launch {
                try {
                    if (!flushLocalEdits()) return@launch
                    val local = state.value.persistence ?: return@launch
                    val result =
                        discardWorkingCopy(
                            target.workspaceId,
                            target.selection,
                            target.kind.action,
                            local.stored.revision,
                        )
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportLocalFailure(result.failure)
                        is AppResult.Success ->
                            if (!result.value) {
                                reportLocalFailure(
                                    Failure(
                                        FailureKind.Conflict,
                                        "The saved draft changed.",
                                        code = "client.overtime_working_copy_changed",
                                    )
                                )
                            } else {
                                updateState { it.copy(closing = true, persistence = null) }
                                emitEffect(OvertimeActionEffect.Back)
                            }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun onDialogDismissed(event: OvertimeActionEvent.DialogDismissed) {
        updateState { it.copy(dialog = null) }
    }

    private fun onBackRequested(event: OvertimeActionEvent.BackRequested) {
        if (!foreground || state.value.closing || state.value.work == OvertimeActionWork.LEAVING)
            return
        if (state.value.dialog != null) onEvent(OvertimeActionEvent.DialogDismissed)
        else {
            operation?.cancel()
            updateState { it.copy(work = OvertimeActionWork.LEAVING) }
            operation =
                viewModelScope.launch {
                    try {
                        if (
                            state.value.restriction == null &&
                                state.value.persistence != null &&
                                !flushLocalEdits()
                        ) {
                            updateState { it.copy(dialog = OvertimeActionDialog.LeaveUnsaved) }
                            return@launch
                        }
                        foreground = false
                        observation?.cancel()
                        observation = null
                        updateState { it.copy(closing = true) }
                        emitEffect(OvertimeActionEffect.Back)
                    } finally {
                        if (isActive) updateState { it.copy(work = null) }
                    }
                }
        }
    }

    private fun onExitWithoutSaving(event: OvertimeActionEvent.ExitWithoutSaving) {
        if (state.value.dialog != OvertimeActionDialog.LeaveUnsaved || state.value.closing) return
        localSave?.cancel()
        foreground = false
        observation?.cancel()
        observation = null
        updateState { it.copy(closing = true, dialog = null) }
        viewModelScope.launch { emitEffect(OvertimeActionEffect.Back) }
    }

    private fun onVerificationRequested(event: OvertimeActionEvent.VerificationRequested) {
        if (foreground && state.value.needsVerification)
            viewModelScope.launch { emitEffect(OvertimeActionEffect.VerifyAccount) }
    }
}

private val transactionWork =
    setOf(
        OvertimeActionWork.SENDING,
        OvertimeActionWork.OPENING,
        OvertimeActionWork.DISCARDING,
        OvertimeActionWork.LEAVING,
    )
