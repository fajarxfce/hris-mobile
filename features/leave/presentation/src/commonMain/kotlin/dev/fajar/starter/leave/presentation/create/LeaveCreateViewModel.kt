package dev.fajar.starter.leave.presentation.create

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.MAX_LEAVE_ATTACHMENTS
import dev.fajar.starter.leave.domain.policies.leaveFailureInvalidatesCache
import dev.fajar.starter.leave.domain.usecases.*
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
    private val refreshRequest: RefreshLeaveRequest,
) :
    MviViewModel<LeaveCreateState, LeaveCreateEvent, LeaveCreateEffect>(
        initialLeaveCreateState(target)
    ) {
    private var foreground = false
    private var observation: Job? = null
    private var operation: Job? = null
    private var expiration: Job? = null

    init {
        on<LeaveCreateEvent.Entered>(::onEntered)
        on<LeaveCreateEvent.Stopped>(::onStopped)
        on<LeaveCreateEvent.ReloadRequested>(::onReloadRequested)
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
                        }
                    }
                }
            }
    }

    private fun onReloadRequested(event: LeaveCreateEvent.ReloadRequested) {
        if (!foreground || state.value.work in transactionWork) return
        loadCatalog(null)
    }

    private fun loadCatalog(after: String?) {
        val form = state.value.form ?: return
        operation?.cancel()
        expiration?.cancel()
        updateState {
            it.copy(
                work = LeaveCreateWork.TYPES,
                preview = null,
                previewExpired = false,
                failure = null,
                catalogAfter = after,
            )
        }
        operation =
            viewModelScope.launch {
                try {
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
                            observeRetainedSubmission()
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
    }

    private fun onReasonChanged(event: LeaveCreateEvent.ReasonChanged) {
        val form = state.value.form ?: return
        if (state.value.editable)
            updateState {
                it.copy(form = form.copy(reason = event.value.take(1_001)), failure = null)
            }
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
                form = form.copy(attachments = form.attachments + event.document),
                evidenceTarget = null,
                failure = null,
            )
        }
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
                    val result = prepareSubmission(target.workspaceId, preview, reason, attachments)
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
            is AppResult.Success ->
                emitEffect(LeaveCreateEffect.OpenRequest(record.command.requestId))
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
            observation?.cancel()
            observation = null
            expiration?.cancel()
            expiration = null
            if (failure.code in workspaceAccessLossCodes && !state.value.needsVerification)
                viewModelScope.launch { emitEffect(LeaveCreateEffect.RecheckWorkspace) }
        }
    }

    private fun onDialogDismissed(event: LeaveCreateEvent.DialogDismissed) {
        updateState { it.copy(dialog = null) }
    }

    private fun onBackRequested(event: LeaveCreateEvent.BackRequested) {
        if (state.value.evidenceTarget != null) updateState { it.copy(evidenceTarget = null) }
        else if (state.value.dialog != null) onEvent(LeaveCreateEvent.DialogDismissed)
        else viewModelScope.launch { emitEffect(LeaveCreateEffect.Back) }
    }

    private fun onVerificationRequested(event: LeaveCreateEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(LeaveCreateEffect.VerifyAccount) }
    }
}

private val transactionWork =
    setOf(LeaveCreateWork.SENDING, LeaveCreateWork.REVIEWING, LeaveCreateWork.DISCARDING)
