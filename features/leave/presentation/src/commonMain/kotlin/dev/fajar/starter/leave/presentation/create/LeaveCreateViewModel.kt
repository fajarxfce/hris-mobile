package dev.fajar.starter.leave.presentation.create

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.MAX_LEAVE_ATTACHMENTS
import dev.fajar.starter.leave.domain.policies.leaveFailureInvalidatesCache
import dev.fajar.starter.leave.domain.usecases.*
import dev.fajar.starter.leave.domain.working.usecases.*
import dev.fajar.starter.leave.presentation.evidence.LeaveEvidenceTarget
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class LeaveCreateViewModel(
    @InjectedParam private val target: LeaveCreateTarget,
    private val loadTypes: LoadLeaveTypes,
    private val previewRequest: PreviewLeaveRequest,
    private val observeSubmission: ObserveLeaveSubmission,
    private val prepareSubmission: PrepareLeaveSubmission,
    private val replaySubmission: ReplayLeaveSubmission,
    private val reviewSubmission: ReviewLeaveSubmission,
    private val refreshRequest: LoadLeaveRequest,
    private val synchronize: SyncLeave,
    private val openWorkingCopy: OpenLeaveWorkingCopy,
    private val saveWorkingCopy: SaveLeaveWorkingCopy,
    private val discardWorkingCopy: DiscardLeaveWorkingCopy,
) :
    MviViewModel<LeaveCreateState, LeaveCreateEvent, LeaveCreateEffect>(
        initialLeaveCreateState(target)
    ) {
    private var foreground = false
    private var observation: Job? = null
    private var operation: Job? = null
    private var expiration: Job? = null
    private var localSave: Job? = null

    init {
        on<LeaveCreateEvent.Entered>(::onEntered)
        on<LeaveCreateEvent.Stopped>(::onStopped)
        on<LeaveCreateEvent.ReloadRequested>(::onReloadRequested)
        on<LeaveCreateEvent.SaveRetryRequested>(::onSaveRetryRequested)
        on<LeaveCreateEvent.ReloadDraftRequested>(::onReloadDraftRequested)
        on<LeaveCreateEvent.ReloadDraftConfirmed>(::onReloadDraftConfirmed)
        on<LeaveCreateEvent.DiscardDraftRequested>(::onDiscardDraftRequested)
        on<LeaveCreateEvent.DiscardDraftConfirmed>(::onDiscardDraftConfirmed)
        on<LeaveCreateEvent.ExitWithoutSaving>(::onExitWithoutSaving)
        on<LeaveCreateEvent.TypePickerRequested>(::onTypePickerRequested)
        on<LeaveCreateEvent.TypePageRequested>(::onTypePageRequested)
        on<LeaveCreateEvent.TypeSelected>(::onTypeSelected)
        on<LeaveCreateEvent.DatePickerRequested>(::onDatePickerRequested)
        on<LeaveCreateEvent.DateMonthRequested>(::onDateMonthRequested)
        on<LeaveCreateEvent.DateSelected>(::onDateSelected)
        on<LeaveCreateEvent.PortionPickerRequested>(::onPortionPickerRequested)
        on<LeaveCreateEvent.PortionSelected>(::onPortionSelected)
        on<LeaveCreateEvent.ReasonChanged>(::onReasonChanged)
        on<LeaveCreateEvent.EvidencePickerRequested>(::onEvidencePickerRequested)
        on<LeaveCreateEvent.EvidenceSelected>(::onEvidenceSelected)
        on<LeaveCreateEvent.EvidenceDismissed>(::onEvidenceDismissed)
        on<LeaveCreateEvent.AttachmentRemoved>(::onAttachmentRemoved)
        on<LeaveCreateEvent.PreviewRequested>(::onPreviewRequested)
        on<LeaveCreateEvent.PreviewExpired>(::onPreviewExpired)
        on<LeaveCreateEvent.SubmitRequested>(::onSubmitRequested)
        on<LeaveCreateEvent.RetryRequested>(::onRetryRequested)
        on<LeaveCreateEvent.OpenRequestRequested>(::onOpenRequestRequested)
        on<LeaveCreateEvent.DiscardRequested>(::onDiscardRequested)
        on<LeaveCreateEvent.DialogDismissed>(::onDialogDismissed)
        on<LeaveCreateEvent.BackRequested>(::onBackRequested)
        on<LeaveCreateEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: LeaveCreateEvent.Entered) {
        if (foreground || state.value.form == null) return
        foreground = true
        observeRetainedSubmission()
        onEvent(LeaveCreateEvent.ReloadRequested)
    }

    private fun onStopped(event: LeaveCreateEvent.Stopped) {
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
                catalog = LeavePage(emptyList()),
                preview = null,
                command = null,
                commandReady = false,
                work = null,
                dialog = null,
                evidenceTarget = null,
            )
        }
    }

    private fun observeRetainedSubmission() {
        observation?.cancel()
        if (!foreground || state.value.restriction != null) return
        observation =
            viewModelScope.launch {
                observeSubmission(target.workspaceId, target.employeeId).collect { result ->
                    ensureActive()
                    if (state.value.restriction != null) return@collect
                    when (result) {
                        is AppResult.Failed -> {
                            updateState { it.copy(command = null, commandReady = false) }
                            reportFailure(result.failure)
                        }
                        is AppResult.Success -> {
                            if (result.value != null) expiration?.cancel()
                            updateState { it.withRetainedSubmission(result.value) }
                            if (result.value == null) persistEdits()
                        }
                    }
                }
            }
    }

    private fun onReloadRequested(event: LeaveCreateEvent.ReloadRequested) {
        if (!foreground || state.value.closing || state.value.work in transactionWork) return
        loadCatalog(null)
    }

    private fun loadCatalog(after: String?, reloadDraft: Boolean = false) {
        if (state.value.form == null) return
        operation?.cancel()
        expiration?.cancel()
        if (reloadDraft && state.value.persistence?.writing != true) localSave?.cancel()
        updateState {
            it.copy(
                work =
                    if (reloadDraft || it.persistence == null) LeaveCreateWork.OPENING
                    else LeaveCreateWork.TYPES,
                preview = null,
                previewExpired = false,
                failure = null,
                catalogAfter = after,
            )
        }
        operation =
            viewModelScope.launch {
                try {
                    if (reloadDraft) {
                        localSave?.join()
                        ensureActive()
                        updateState { it.copy(persistence = null, dialog = null) }
                    }
                    val recovery = synchronize.recoverAccess(target.workspaceId)
                    ensureActive()
                    if (recovery is AppResult.Failed) {
                        reportFailure(recovery.failure)
                        return@launch
                    }
                    if (state.value.persistence == null) {
                        val date = LocalDate.parse(target.asOf)
                        var local = openWorkingCopy(target.workspaceId, target.employeeId, date)
                        ensureActive()
                        if (
                            local is AppResult.Failed &&
                                local.failure.code == "client.leave_working_copy_unavailable"
                        ) {
                            val synced = synchronize(target.workspaceId)
                            ensureActive()
                            local =
                                when (synced) {
                                    is AppResult.Failed -> synced
                                    is AppResult.Success ->
                                        openWorkingCopy(target.workspaceId, target.employeeId, date)
                                }
                        }
                        ensureActive()
                        when (local) {
                            is AppResult.Failed -> {
                                reportFailure(local.failure)
                                return@launch
                            }
                            is AppResult.Success ->
                                updateState {
                                    it.copy(
                                        form = local.value.input,
                                        persistence = LeaveEditPersistence(local.value),
                                        restriction = null,
                                    )
                                }
                        }
                    }
                    val form = state.value.form ?: return@launch
                    updateState { it.copy(work = LeaveCreateWork.TYPES) }
                    val reopened = observation?.isActive != true
                    if (reopened) observeRetainedSubmission()
                    val result = loadTypes(target.workspaceId, LeaveTypeSelection(form.from, after))
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState {
                                it.copy(catalog = result.value, restriction = null, failure = null)
                            }
                            // A fresh admitted read may reopen a command source that previously
                            // ended.
                            if (!reopened) observeRetainedSubmission()
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun onTypePickerRequested(event: LeaveCreateEvent.TypePickerRequested) {
        if (state.value.editable && state.value.evidenceTarget == null)
            updateState { it.copy(dialog = LeaveCreateDialog.Types) }
    }

    private fun onTypePageRequested(event: LeaveCreateEvent.TypePageRequested) {
        if (
            !state.value.editable ||
                state.value.dialog != LeaveCreateDialog.Types ||
                (event.after != null && event.after != state.value.catalog.nextCursor) ||
                event.after == state.value.catalogAfter
        )
            return
        loadCatalog(event.after)
    }

    private fun onTypeSelected(event: LeaveCreateEvent.TypeSelected) {
        val form = state.value.form ?: return
        val type = state.value.catalog.items.singleOrNull { it.id == event.id } ?: return
        if (
            !state.value.editable ||
                state.value.work == LeaveCreateWork.TYPES ||
                state.value.dialog != LeaveCreateDialog.Types
        )
            return
        operation?.cancel()
        operation = null
        expiration?.cancel()
        updateState {
            it.copy(
                form =
                    form.copy(
                        type = type,
                        portion = if (type.allowPartialDays) form.portion else LeavePortion.FULL,
                    ),
                dialog = null,
                preview = null,
                previewExpired = false,
                failure = null,
                work = null,
            )
        }
        stageEdits()
    }

    private fun onDatePickerRequested(event: LeaveCreateEvent.DatePickerRequested) {
        val form = state.value.form ?: return
        if (!state.value.editable || state.value.evidenceTarget != null) return
        val selected = if (event.field == LeaveDateField.FROM) form.from else form.until
        updateState {
            it.copy(
                dialog =
                    LeaveCreateDialog.Date(event.field, LocalDate(selected.year, selected.month, 1))
            )
        }
    }

    private fun onDateMonthRequested(event: LeaveCreateEvent.DateMonthRequested) {
        val dialog = state.value.dialog as? LeaveCreateDialog.Date ?: return
        if (!state.value.editable || event.delta !in setOf(-1, 1)) return
        val next = dialog.month.plus(event.delta, DateTimeUnit.MONTH)
        if (next.year !in 1900..2200) return
        updateState { it.copy(dialog = dialog.copy(month = next)) }
    }

    private fun onDateSelected(event: LeaveCreateEvent.DateSelected) {
        val form = state.value.form ?: return
        val dialog = state.value.dialog as? LeaveCreateDialog.Date ?: return
        if (
            !state.value.editable ||
                event.day !in 1..dialog.month.daysUntil(dialog.month.plus(1, DateTimeUnit.MONTH))
        )
            return
        val selected = LocalDate(dialog.month.year, dialog.month.month, event.day)
        if (dialog.field == LeaveDateField.UNTIL && form.from.daysUntil(selected) !in 0..365) return
        operation?.cancel()
        operation = null
        expiration?.cancel()
        val updated =
            if (dialog.field == LeaveDateField.FROM)
                form.copy(
                    from = selected,
                    until = form.until.takeIf { selected.daysUntil(it) in 0..365 } ?: selected,
                    type = null,
                )
            else form.copy(until = selected)
        updateState {
            it.copy(
                form = updated,
                dialog = null,
                preview = null,
                previewExpired = false,
                failure = null,
                work = null,
            )
        }
        if (dialog.field == LeaveDateField.FROM) loadCatalog(null)
        stageEdits()
    }

    private fun onPortionPickerRequested(event: LeaveCreateEvent.PortionPickerRequested) {
        if (
            state.value.editable &&
                state.value.evidenceTarget == null &&
                state.value.form?.type?.allowPartialDays == true
        )
            updateState { it.copy(dialog = LeaveCreateDialog.Portion) }
    }

    private fun onPortionSelected(event: LeaveCreateEvent.PortionSelected) {
        val form = state.value.form ?: return
        if (
            !state.value.editable ||
                state.value.dialog != LeaveCreateDialog.Portion ||
                (event.portion != LeavePortion.FULL && form.type?.allowPartialDays != true)
        )
            return
        updateState { it.copy(dialog = null) }
        if (form.portion == event.portion) return
        operation?.cancel()
        operation = null
        expiration?.cancel()
        updateState {
            it.copy(
                form = form.copy(portion = event.portion),
                preview = null,
                previewExpired = false,
                failure = null,
                work = null,
            )
        }
        stageEdits()
    }

    private fun onReasonChanged(event: LeaveCreateEvent.ReasonChanged) {
        val form = state.value.form ?: return
        if (
            foreground &&
                state.value.editable &&
                state.value.dialog == null &&
                state.value.evidenceTarget == null
        )
            updateState {
                it.copy(form = form.copy(reason = event.value.take(1_001)), failure = null)
            }
        stageEdits()
    }

    private fun onEvidencePickerRequested(event: LeaveCreateEvent.EvidencePickerRequested) {
        if (!foreground || !state.value.canAddAttachment || state.value.dialog != null) return
        val form = state.value.form ?: return
        updateState {
            it.copy(
                evidenceTarget =
                    LeaveEvidenceTarget(
                        target.workspaceId,
                        target.employeeId,
                        form.attachments.map { attachment -> attachment.documentId }.toSet(),
                    )
            )
        }
    }

    private fun onEvidenceSelected(event: LeaveCreateEvent.EvidenceSelected) {
        val form = state.value.form ?: return
        if (
            !foreground ||
                !state.value.editable ||
                state.value.evidenceTarget !== event.target ||
                form.attachments.size >= MAX_LEAVE_ATTACHMENTS ||
                form.attachments.any {
                    it.documentId == event.document.documentId ||
                        it.revisionId == event.document.revisionId
                }
        )
            return
        updateState {
            it.copy(
                form =
                    form.copy(attachments = form.attachments + event.document.toLeaveAttachment()),
                evidenceTarget = null,
                failure = null,
            )
        }
        stageEdits()
    }

    private fun onEvidenceDismissed(event: LeaveCreateEvent.EvidenceDismissed) {
        if (state.value.evidenceTarget === event.target)
            updateState { it.copy(evidenceTarget = null) }
    }

    private fun onAttachmentRemoved(event: LeaveCreateEvent.AttachmentRemoved) {
        val form = state.value.form ?: return
        if (!foreground || !state.value.editable || state.value.evidenceTarget != null) return
        updateState {
            it.copy(
                failure = null,
                form =
                    form.copy(
                        attachments =
                            form.attachments.filterNot { attachment ->
                                attachment.revisionId == event.revisionId
                            }
                    ),
            )
        }
        stageEdits()
    }

    private fun onPreviewRequested(event: LeaveCreateEvent.PreviewRequested) {
        if (!foreground || !state.value.canPreview) return
        val form = state.value.form ?: return
        val selection =
            when (val result = form.toSelection(target.employeeId)) {
                is AppResult.Failed -> {
                    reportFailure(result.failure)
                    return
                }
                is AppResult.Success -> result.value
            }
        expiration?.cancel()
        updateState {
            it.copy(
                work = LeaveCreateWork.PREVIEW,
                preview = null,
                previewExpired = false,
                failure = null,
            )
        }
        operation =
            viewModelScope.launch {
                try {
                    when (val result = previewRequest(target.workspaceId, selection)) {
                        is AppResult.Failed -> {
                            ensureActive()
                            reportFailure(result.failure)
                        }
                        is AppResult.Success -> {
                            ensureActive()
                            updateState { it.copy(preview = result.value, previewExpired = false) }
                            expiration =
                                viewModelScope.launch {
                                    delay(result.value.expiresAt - result.value.fetchedAt)
                                    onEvent(LeaveCreateEvent.PreviewExpired(result.value))
                                }
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun onPreviewExpired(event: LeaveCreateEvent.PreviewExpired) {
        if (state.value.preview == event.preparation) updateState { it.copy(previewExpired = true) }
    }

    private fun onSubmitRequested(event: LeaveCreateEvent.SubmitRequested) {
        if (!foreground || !state.value.canSubmit) return
        val preview = state.value.preview ?: return
        val reason = state.value.form?.reason ?: return
        val attachments = state.value.form?.attachments?.map { it.revisionId } ?: return
        updateState { it.copy(work = LeaveCreateWork.SENDING, failure = null) }
        operation =
            viewModelScope.launch {
                try {
                    if (!flushLocalEdits()) return@launch
                    val revision = state.value.persistence?.stored?.revision ?: return@launch
                    val result =
                        prepareSubmission(
                            target.workspaceId,
                            preview,
                            reason,
                            attachments,
                            revision,
                        )
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            expiration?.cancel()
                            updateState { it.withRetainedSubmission(result.value) }
                            sendRetainedSubmission(result.value.command.id)
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun onRetryRequested(event: LeaveCreateEvent.RetryRequested) {
        if (!foreground || !state.value.canRetry) return
        val id = state.value.command?.command?.id ?: return
        updateState { it.copy(work = LeaveCreateWork.SENDING, failure = null) }
        operation =
            viewModelScope.launch {
                try {
                    sendRetainedSubmission(id)
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private suspend fun sendRetainedSubmission(id: String) {
        val result = replaySubmission(target.workspaceId, id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Failed -> reportFailure(result.failure)
            is AppResult.Success -> {
                updateState { it.withRetainedSubmission(result.value) }
                when (val outcome = result.value.outcome) {
                    is LeaveCommandOutcome.Confirmed -> reconcileAndOpen(result.value)
                    is LeaveCommandOutcome.Unconfirmed -> outcome.failure?.let(::reportFailure)
                    is LeaveCommandOutcome.Rejected -> reportFailure(outcome.failure)
                    LeaveCommandOutcome.Pending -> Unit
                }
            }
        }
    }

    private fun onOpenRequestRequested(event: LeaveCreateEvent.OpenRequestRequested) {
        val record = state.value.command ?: return
        if (
            !foreground ||
                state.value.restriction != null ||
                state.value.work != null ||
                record.outcome !is LeaveCommandOutcome.Confirmed
        )
            return
        updateState { it.copy(work = LeaveCreateWork.REVIEWING, failure = null) }
        operation =
            viewModelScope.launch {
                try {
                    reconcileAndOpen(record)
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private suspend fun reconcileAndOpen(record: LeaveSubmissionRecord) {
        val acquired =
            refreshRequest(target.workspaceId, LeaveRequestSelection(record.command.requestId))
        currentCoroutineContext().ensureActive()
        if (acquired is AppResult.Failed) {
            reportFailure(acquired.failure)
            return
        }
        val reviewed = reviewSubmission(target.workspaceId, record.command.id)
        currentCoroutineContext().ensureActive()
        when (reviewed) {
            is AppResult.Failed -> reportFailure(reviewed.failure)
            is AppResult.Success -> {
                updateState { it.copy(closing = true, persistence = null) }
                emitEffect(LeaveCreateEffect.OpenRequest(record.command.requestId))
            }
        }
    }

    private fun onDiscardRequested(event: LeaveCreateEvent.DiscardRequested) {
        if (!foreground || !state.value.canDiscard) return
        val id = state.value.command?.command?.id ?: return
        updateState { it.copy(work = LeaveCreateWork.DISCARDING, failure = null) }
        operation =
            viewModelScope.launch {
                try {
                    val result = reviewSubmission(target.workspaceId, id)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            if (result.value)
                                updateState {
                                    it.copy(command = null, commandReady = false, preview = null)
                                }
                            observeRetainedSubmission()
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun reportFailure(failure: Failure) {
        updateState { it.withCreateFailure(failure) }
        if (leaveFailureInvalidatesCache(failure)) {
            localSave?.cancel()
            observation?.cancel()
            observation = null
            expiration?.cancel()
            expiration = null
            if (failure.code in workspaceAccessLossCodes && !state.value.needsVerification)
                viewModelScope.launch { emitEffect(LeaveCreateEffect.RecheckWorkspace) }
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
                        current.pending ?: LeaveWorkingMutation(current.stored, current.edited)
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
        if (leaveFailureInvalidatesCache(failure)) reportFailure(failure)
    }

    private fun onSaveRetryRequested(event: LeaveCreateEvent.SaveRetryRequested) {
        val local = state.value.persistence ?: return
        if (
            !foreground ||
                state.value.closing ||
                state.value.restriction != null ||
                state.value.work in transactionWork ||
                local.writing ||
                local.failure == null ||
                local.failure.code == "client.leave_working_copy_changed"
        )
            return
        updateState { it.copy(persistence = local.copy(failure = null)) }
        persistEdits(immediate = true)
    }

    private fun onReloadDraftRequested(event: LeaveCreateEvent.ReloadDraftRequested) {
        if (
            foreground &&
                !state.value.closing &&
                state.value.work == null &&
                state.value.restriction == null &&
                state.value.persistence != null &&
                state.value.command == null
        )
            updateState { it.copy(dialog = LeaveCreateDialog.ReloadDraft) }
    }

    private fun onReloadDraftConfirmed(event: LeaveCreateEvent.ReloadDraftConfirmed) {
        if (
            foreground &&
                state.value.dialog == LeaveCreateDialog.ReloadDraft &&
                state.value.work == null
        )
            loadCatalog(null, reloadDraft = true)
    }

    private fun onDiscardDraftRequested(event: LeaveCreateEvent.DiscardDraftRequested) {
        if (
            foreground &&
                state.value.editable &&
                state.value.work == null &&
                state.value.evidenceTarget == null
        )
            updateState { it.copy(dialog = LeaveCreateDialog.DiscardDraft) }
    }

    private fun onDiscardDraftConfirmed(event: LeaveCreateEvent.DiscardDraftConfirmed) {
        if (
            !foreground ||
                state.value.dialog != LeaveCreateDialog.DiscardDraft ||
                state.value.work != null ||
                !state.value.editable
        )
            return
        updateState { it.copy(dialog = null, work = LeaveCreateWork.DISCARDING) }
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
                                        code = "client.leave_working_copy_changed",
                                    )
                                )
                            } else {
                                updateState { it.copy(closing = true, persistence = null) }
                                emitEffect(LeaveCreateEffect.Back)
                            }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun onDialogDismissed(event: LeaveCreateEvent.DialogDismissed) {
        updateState { it.copy(dialog = null) }
    }

    private fun onBackRequested(event: LeaveCreateEvent.BackRequested) {
        if (state.value.closing || state.value.work == LeaveCreateWork.LEAVING) return
        if (state.value.evidenceTarget != null) updateState { it.copy(evidenceTarget = null) }
        else if (state.value.dialog != null) onEvent(LeaveCreateEvent.DialogDismissed)
        else {
            operation?.cancel()
            expiration?.cancel()
            updateState { it.copy(work = LeaveCreateWork.LEAVING) }
            operation =
                viewModelScope.launch {
                    try {
                        if (
                            state.value.restriction == null &&
                                state.value.persistence != null &&
                                !flushLocalEdits()
                        ) {
                            updateState { it.copy(dialog = LeaveCreateDialog.LeaveUnsaved) }
                            return@launch
                        }
                        updateState { it.copy(closing = true) }
                        emitEffect(LeaveCreateEffect.Back)
                    } finally {
                        if (isActive) updateState { it.copy(work = null) }
                    }
                }
        }
    }

    private fun onExitWithoutSaving(event: LeaveCreateEvent.ExitWithoutSaving) {
        if (state.value.dialog != LeaveCreateDialog.LeaveUnsaved || state.value.closing) return
        localSave?.cancel()
        updateState { it.copy(closing = true, dialog = null) }
        viewModelScope.launch { emitEffect(LeaveCreateEffect.Back) }
    }

    private fun onVerificationRequested(event: LeaveCreateEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(LeaveCreateEffect.VerifyAccount) }
    }
}

private val transactionWork =
    setOf(
        LeaveCreateWork.SENDING,
        LeaveCreateWork.REVIEWING,
        LeaveCreateWork.DISCARDING,
        LeaveCreateWork.LEAVING,
    )
