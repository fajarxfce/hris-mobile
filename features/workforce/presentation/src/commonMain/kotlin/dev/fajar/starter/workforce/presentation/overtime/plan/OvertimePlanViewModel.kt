package dev.fajar.starter.workforce.presentation.overtime.plan

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailureInvalidatesCache
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import dev.fajar.starter.workforce.domain.overtime.working.usecases.*
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
    private val refreshRequest: LoadOvertimeRequest,
    private val synchronize: SyncOvertime,
    private val openWorkingCopy: OpenOvertimePlanWorkingCopy,
    private val saveWorkingCopy: SaveOvertimePlanWorkingCopy,
    private val discardWorkingCopy: DiscardOvertimePlanWorkingCopy,
) :
    MviViewModel<OvertimePlanState, OvertimePlanEvent, OvertimePlanEffect>(
        initialOvertimePlanState(target)
    ) {
    private var foreground = false
    private var observation: Job? = null
    private var operation: Job? = null
    private var expiration: Job? = null
    private var localSave: Job? = null

    init {
        on<OvertimePlanEvent.Entered>(::onEntered)
        on<OvertimePlanEvent.Stopped>(::onStopped)
        on<OvertimePlanEvent.ReloadRequested>(::onReloadRequested)
        on<OvertimePlanEvent.SaveRetryRequested>(::onSaveRetryRequested)
        on<OvertimePlanEvent.ReloadDraftRequested>(::onReloadDraftRequested)
        on<OvertimePlanEvent.ReloadDraftConfirmed>(::onReloadDraftConfirmed)
        on<OvertimePlanEvent.DiscardDraftRequested>(::onDiscardDraftRequested)
        on<OvertimePlanEvent.DiscardDraftConfirmed>(::onDiscardDraftConfirmed)
        on<OvertimePlanEvent.ExitWithoutSaving>(::onExitWithoutSaving)
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
        if (foreground || state.value.closing || state.value.form == null) return
        foreground = true
        if (state.value.restriction != null) onEvent(OvertimePlanEvent.ReloadRequested)
        else observeRetainedPlan()
    }

    private fun onStopped(event: OvertimePlanEvent.Stopped) {
        foreground = false
        persistEdits(immediate = true)
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
        if (!foreground || state.value.closing || state.value.work in transactionWork) return
        loadReview()
    }

    private fun loadReview(reloadDraft: Boolean = false) {
        if (state.value.form == null) return
        operation?.cancel()
        expiration?.cancel()
        if (reloadDraft && state.value.persistence?.writing != true) localSave?.cancel()
        updateState {
            it.copy(
                work =
                    if (reloadDraft || it.persistence == null) OvertimePlanWork.RESTORING
                    else OvertimePlanWork.REVIEW,
                prepared = null,
                reviewExpired = false,
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
                    val local =
                        if (state.value.persistence == null) {
                            val date = LocalDate.parse(target.workDate)
                            var result =
                                openWorkingCopy(target.workspaceId, target.employeeId, date)
                            ensureActive()
                            if (
                                result is AppResult.Failed &&
                                    result.failure.code ==
                                        "client.overtime_working_copy_unavailable"
                            ) {
                                val synced = synchronize(target.workspaceId)
                                ensureActive()
                                result =
                                    when (synced) {
                                        is AppResult.Failed -> synced
                                        is AppResult.Success ->
                                            openWorkingCopy(
                                                target.workspaceId,
                                                target.employeeId,
                                                date,
                                            )
                                    }
                            }
                            ensureActive()
                            when (result) {
                                is AppResult.Failed -> {
                                    reportFailure(result.failure)
                                    return@launch
                                }
                                is AppResult.Success -> OvertimePlanEditPersistence(result.value)
                            }
                        } else requireNotNull(state.value.persistence)
                    // Restore owned input before live review, but keep a confirmed restriction
                    // closed.
                    if (state.value.restriction == null) {
                        updateState { it.copy(form = local.edited.input, persistence = local) }
                    }
                    updateState { it.copy(work = OvertimePlanWork.REVIEW) }
                    val result =
                        reviewSchedule(
                            target.workspaceId,
                            OvertimePlanningSelection(
                                target.employeeId,
                                local.edited.input.workDate,
                                target.employmentAfter,
                            ),
                        )
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState { it.withReviewedSchedule(result.value, local) }
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
        stageEdits()
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
        stageEdits()
    }

    private fun onBreakChanged(event: OvertimePlanEvent.BreakChanged) {
        val form = state.value.form ?: return
        if (
            !foreground ||
                !state.value.editable ||
                event.value.length > 3 ||
                event.value.any { it !in '0'..'9' }
        )
            return
        updateState { it.copy(form = form.copy(breakMinutes = event.value), failure = null) }
        stageEdits()
    }

    private fun onReasonChanged(event: OvertimePlanEvent.ReasonChanged) {
        val form = state.value.form ?: return
        if (!foreground || !state.value.editable) return
        updateState { it.copy(form = form.copy(reason = event.value.take(1_001)), failure = null) }
        stageEdits()
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
                    if (!flushLocalEdits()) return@launch
                    val revision = state.value.persistence?.stored?.revision ?: return@launch
                    val result =
                        preparePlan(target.workspaceId, prepared, input, form.reason, revision)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed ->
                            if (result.failure.code == "client.overtime_working_copy_changed")
                                reportLocalFailure(result.failure)
                            else reportFailure(result.failure)
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
            is AppResult.Success -> {
                updateState { it.copy(closing = true, persistence = null) }
                emitEffect(OvertimePlanEffect.OpenRequest(selection))
            }
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
        val previousCode = state.value.restriction?.code
        updateState { it.withPlanFailure(failure) }
        if (overtimeFailureInvalidatesCache(failure)) {
            localSave?.cancel()
            localSave = null
            observation?.cancel()
            observation = null
            expiration?.cancel()
            expiration = null
            // The use case may still be persisting this denial or a delivery outcome.
            // Its deadline bounds completion; leaving the route still cancels it.
            if (
                failure.code != previousCode &&
                    failure.code in workspaceAccessLossCodes &&
                    !state.value.needsVerification
            )
                viewModelScope.launch { emitEffect(OvertimePlanEffect.RecheckWorkspace) }
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
                            ?: OvertimePlanWorkingMutation(current.stored, current.edited)
                    updateState {
                        it.copy(
                            persistence = it.persistence?.copy(pending = mutation, writing = true)
                        )
                    }
                    val result =
                        saveWorkingCopy(target.workspaceId, mutation.previous, mutation.updated)
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

    private fun onSaveRetryRequested(event: OvertimePlanEvent.SaveRetryRequested) {
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

    private fun onReloadDraftRequested(event: OvertimePlanEvent.ReloadDraftRequested) {
        if (
            foreground &&
                !state.value.closing &&
                state.value.work == null &&
                state.value.restriction == null &&
                state.value.persistence != null &&
                state.value.command == null
        )
            updateState { it.copy(dialog = OvertimePlanDialog.ReloadDraft) }
    }

    private fun onReloadDraftConfirmed(event: OvertimePlanEvent.ReloadDraftConfirmed) {
        if (
            foreground &&
                state.value.dialog == OvertimePlanDialog.ReloadDraft &&
                state.value.work == null
        )
            loadReview(reloadDraft = true)
    }

    private fun onDiscardDraftRequested(event: OvertimePlanEvent.DiscardDraftRequested) {
        if (foreground && state.value.editable && state.value.work == null)
            updateState { it.copy(dialog = OvertimePlanDialog.DiscardDraft) }
    }

    private fun onDiscardDraftConfirmed(event: OvertimePlanEvent.DiscardDraftConfirmed) {
        if (
            !foreground ||
                state.value.dialog != OvertimePlanDialog.DiscardDraft ||
                state.value.work != null ||
                !state.value.editable
        )
            return
        updateState { it.copy(dialog = null, work = OvertimePlanWork.DISCARDING) }
        operation =
            viewModelScope.launch {
                try {
                    if (!flushLocalEdits()) return@launch
                    val local = state.value.persistence ?: return@launch
                    val result =
                        discardWorkingCopy(
                            target.workspaceId,
                            target.employeeId,
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
                                emitEffect(OvertimePlanEffect.Back)
                            }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun onDialogDismissed(event: OvertimePlanEvent.DialogDismissed) {
        updateState { it.copy(dialog = null) }
    }

    private fun onBackRequested(event: OvertimePlanEvent.BackRequested) {
        if (state.value.closing || state.value.work == OvertimePlanWork.LEAVING) return
        if (state.value.dialog != null) onEvent(OvertimePlanEvent.DialogDismissed)
        else {
            operation?.cancel()
            expiration?.cancel()
            updateState { it.copy(work = OvertimePlanWork.LEAVING) }
            operation =
                viewModelScope.launch {
                    try {
                        if (
                            state.value.restriction == null &&
                                state.value.persistence != null &&
                                !flushLocalEdits()
                        ) {
                            updateState { it.copy(dialog = OvertimePlanDialog.LeaveUnsaved) }
                            return@launch
                        }
                        foreground = false
                        observation?.cancel()
                        observation = null
                        updateState { it.copy(closing = true) }
                        emitEffect(OvertimePlanEffect.Back)
                    } finally {
                        if (isActive) updateState { it.copy(work = null) }
                    }
                }
        }
    }

    private fun onExitWithoutSaving(event: OvertimePlanEvent.ExitWithoutSaving) {
        if (state.value.dialog != OvertimePlanDialog.LeaveUnsaved || state.value.closing) return
        localSave?.cancel()
        foreground = false
        observation?.cancel()
        observation = null
        expiration?.cancel()
        expiration = null
        updateState { it.copy(closing = true, dialog = null) }
        viewModelScope.launch { emitEffect(OvertimePlanEffect.Back) }
    }

    private fun onVerificationRequested(event: OvertimePlanEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(OvertimePlanEffect.VerifyAccount) }
    }
}

private val transactionWork =
    setOf(
        OvertimePlanWork.SENDING,
        OvertimePlanWork.OPENING,
        OvertimePlanWork.DISCARDING,
        OvertimePlanWork.LEAVING,
    )
