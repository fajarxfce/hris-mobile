package dev.fajar.starter.expenses.presentation.action

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.usecases.LoadExpenseClaim
import dev.fajar.starter.expenses.domain.replica.usecases.SyncExpenses
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.expenses.presentation.commands.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ExpenseActionViewModel(
    @InjectedParam private val target: ExpenseActionTarget,
    private val observeClaim: ObserveExpenseClaim,
    private val refreshClaim: LoadExpenseClaim,
    private val sync: SyncExpenses,
    private val observeCommand: ObserveExpenseCommand,
    private val prepare: PrepareExpenseAction,
    private val replay: ReplayExpenseCommands,
    private val review: ReviewExpenseCommand,
) :
    MviViewModel<ExpenseActionState, ExpenseActionEvent, ExpenseActionEffect>(
        ExpenseActionState(target.mode)
    ) {
    private var reads: Job? = null
    private var operation: Job? = null

    init {
        on<ExpenseActionEvent.Entered>(::onEntered)
        on<ExpenseActionEvent.Stopped>(::onStopped)
        on<ExpenseActionEvent.RefreshRequested>(::onRefreshRequested)
        on<ExpenseActionEvent.ReasonChanged>(::onReasonChanged)
        on<ExpenseActionEvent.ReviewLatestRequested>(::onReviewLatestRequested)
        on<ExpenseActionEvent.Confirmed>(::onConfirmed)
        on<ExpenseActionEvent.RetryRequested>(::onRetryRequested)
        on<ExpenseActionEvent.OpenClaimRequested>(::onOpenClaimRequested)
        on<ExpenseActionEvent.EditRetainedDraftRequested>(::onEditRetainedDraftRequested)
        on<ExpenseActionEvent.DiscardRequested>(::onDiscardRequested)
        on<ExpenseActionEvent.DiscardConfirmed>(::onDiscardConfirmed)
        on<ExpenseActionEvent.DialogDismissed>(::onDialogDismissed)
        on<ExpenseActionEvent.BackRequested>(::onBackRequested)
        on<ExpenseActionEvent.ExitConfirmed>(::onExitConfirmed)
        on<ExpenseActionEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: ExpenseActionEvent.Entered) {
        if (state.value.active || state.value.closing) return
        updateState { it.copy(active = true, commandReady = false, loading = true) }
        observeReads()
        onRefreshRequested(ExpenseActionEvent.RefreshRequested)
    }

    private fun onStopped(event: ExpenseActionEvent.Stopped) {
        reads?.cancel()
        reads = null
        operation?.cancel()
        operation = null
        updateState {
            it.copy(
                active = false,
                content = null,
                command = null,
                commandReady = false,
                loading = false,
                work = null,
                dialog = null,
            )
        }
    }

    private fun observeReads() {
        reads?.cancel()
        if (!state.value.active || state.value.restriction != null) return
        reads =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                combine(
                        observeClaim(target.workspaceId, target.claimId),
                        observeCommand(target.workspaceId, ExpenseCommandSlot.Claim(target.claimId)),
                    ) { claim, command ->
                        claim to command
                    }
                    .collect { (claim, command) ->
                        ensureActive()
                        if (state.value.restriction != null) return@collect
                        when (claim) {
                            is AppResult.Failed -> {
                                updateState { it.copy(content = null, loading = false) }
                                reportFailure(claim.failure)
                            }
                            is AppResult.Success -> updateState { it.withSnapshot(claim.value) }
                        }
                        if (state.value.restriction != null) return@collect
                        when (command) {
                            is AppResult.Failed -> {
                                updateState { it.copy(command = null, commandReady = false) }
                                reportFailure(command.failure)
                            }
                            is AppResult.Success -> updateState { it.withCommand(command.value) }
                        }
                    }
            }
        reads?.start()
    }

    private fun onRefreshRequested(event: ExpenseActionEvent.RefreshRequested) {
        if (!state.value.active || state.value.closing || state.value.work != null) return
        updateState {
            it.copy(
                work = ExpenseActionWork.REFRESHING,
                loading = it.content == null,
                failure = null,
                dialog = null,
            )
        }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (state.value.restriction != null) {
                        when (val synchronized = sync(target.workspaceId)) {
                            is AppResult.Failed -> {
                                ensureActive()
                                reportFailure(synchronized.failure)
                                return@launch
                            }
                            is AppResult.Success -> Unit
                        }
                    }
                    val result = refreshClaim(target.workspaceId, target.claimId)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState { it.copy(restriction = null, failure = null) }
                            observeReads()
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null, loading = false) }
                }
            }
        operation?.start()
    }

    private fun onReasonChanged(event: ExpenseActionEvent.ReasonChanged) {
        if (state.value.editable)
            updateState {
                it.copy(form = it.form?.copy(reason = event.value.take(1_001)), failure = null)
            }
    }

    private fun onReviewLatestRequested(event: ExpenseActionEvent.ReviewLatestRequested) {
        val snapshot = state.value.content ?: return
        if (
            state.value.active &&
                !state.value.closing &&
                state.value.available &&
                state.value.changed &&
                state.value.work == null &&
                state.value.command == null
        )
            updateState {
                it.copy(
                    form = it.form?.copy(version = snapshot.value.claim.version),
                    failure = null,
                )
            }
    }

    private fun onConfirmed(event: ExpenseActionEvent.Confirmed) {
        val snapshot = state.value.content ?: return
        val form = state.value.form ?: return
        val action = state.value.mode.action ?: return
        if (!state.value.canConfirm) return
        updateState { it.copy(work = ExpenseActionWork.SENDING, failure = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result =
                        prepare(target.workspaceId, snapshot, action, form.reason, form.version)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState { it.withCommand(result.value) }
                            send(result.value.command.id)
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
        operation?.start()
    }

    private fun onRetryRequested(event: ExpenseActionEvent.RetryRequested) {
        val record = state.value.command ?: return
        if (
            !state.value.canRecover ||
                !record.canRetry() ||
                record.command.payload is ExpenseCommandPayload.SaveDraft
        )
            return
        updateState { it.copy(work = ExpenseActionWork.SENDING, failure = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    send(record.command.id)
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
        operation?.start()
    }

    private suspend fun send(id: String) {
        val result = replay(target.workspaceId, id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Failed -> reportFailure(result.failure)
            is AppResult.Success -> {
                updateState { it.withCommand(result.value) }
                when (val outcome = result.value.outcome) {
                    is ExpenseCommandOutcome.Confirmed -> openClaim(result.value)
                    is ExpenseCommandOutcome.Rejected -> reportFailure(outcome.failure)
                    is ExpenseCommandOutcome.Unconfirmed -> outcome.failure?.let(::reportFailure)
                    ExpenseCommandOutcome.Pending -> Unit
                }
            }
        }
    }

    private fun onOpenClaimRequested(event: ExpenseActionEvent.OpenClaimRequested) {
        val record = state.value.command
        if (
            !state.value.canRecover ||
                record != null && record.outcome !is ExpenseCommandOutcome.Confirmed
        )
            return
        updateState { it.copy(work = ExpenseActionWork.OPENING, failure = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    openClaim(record)
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
        operation?.start()
    }

    private suspend fun openClaim(record: ExpenseCommandRecord?) {
        val refreshed = refreshClaim(target.workspaceId, target.claimId)
        currentCoroutineContext().ensureActive()
        if (refreshed is AppResult.Failed) {
            reportFailure(refreshed.failure)
            return
        }
        if (record != null) {
            val reconciled = review(target.workspaceId, record.command.id)
            currentCoroutineContext().ensureActive()
            if (reconciled is AppResult.Failed) {
                reportFailure(reconciled.failure)
                return
            }
        }
        if (!state.value.closing) {
            updateState { it.copy(closing = true) }
            emitEffect(ExpenseActionEffect.OpenClaim(target.claimId, target.employmentAfter))
        }
    }

    private fun onEditRetainedDraftRequested(event: ExpenseActionEvent.EditRetainedDraftRequested) {
        val command = state.value.command?.command ?: return
        if (!state.value.canRecover || command.payload !is ExpenseCommandPayload.SaveDraft) return
        updateState { it.copy(closing = true) }
        viewModelScope.launch {
            emitEffect(
                ExpenseActionEffect.EditDraft(
                    command.employeeId,
                    command.claimId,
                    command.employmentAfter,
                )
            )
        }
    }

    private fun onDiscardRequested(event: ExpenseActionEvent.DiscardRequested) {
        val record = state.value.command ?: return
        if (
            state.value.canRecover &&
                record.canDiscard() &&
                record.command.payload !is ExpenseCommandPayload.SaveDraft
        )
            updateState { it.copy(dialog = ExpenseActionDialog.DISCARD_COMMAND) }
    }

    private fun onDiscardConfirmed(event: ExpenseActionEvent.DiscardConfirmed) {
        val record = state.value.command ?: return
        if (
            !state.value.canRecover ||
                state.value.dialog != ExpenseActionDialog.DISCARD_COMMAND ||
                !record.canDiscard() ||
                record.command.payload is ExpenseCommandPayload.SaveDraft
        )
            return
        updateState { it.copy(work = ExpenseActionWork.DISCARDING, dialog = null, failure = null) }
        operation =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = review(target.workspaceId, record.command.id)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success ->
                            if (result.value) {
                                updateState {
                                    it.copy(
                                        command = null,
                                        mode = record.command.payload.toActionMode(),
                                        form =
                                            ExpenseActionForm(
                                                record.command.expectedReceiptVersion() - 1,
                                                record.command.reason,
                                            ),
                                    )
                                }
                            }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
        operation?.start()
    }

    private fun reportFailure(failure: Failure) {
        val denied = expenseFailureInvalidatesCache(failure)
        if (denied) {
            reads?.cancel()
            operation?.cancel()
        }
        updateState {
            if (denied)
                it.copy(
                    restriction = failure,
                    content = null,
                    command = null,
                    commandReady = false,
                    loading = false,
                    work = null,
                    dialog = null,
                )
            else it.copy(failure = failure)
        }
        if (failure.code in workspaceAccessLossCodes && !state.value.needsVerification)
            viewModelScope.launch { emitEffect(ExpenseActionEffect.RecheckWorkspace) }
    }

    private fun onDialogDismissed(event: ExpenseActionEvent.DialogDismissed) {
        updateState { it.copy(dialog = null) }
    }

    private fun onBackRequested(event: ExpenseActionEvent.BackRequested) {
        if (state.value.closing) return
        if (state.value.dialog != null) updateState { it.copy(dialog = null) }
        else if (state.value.command == null && !state.value.form?.reason.isNullOrBlank())
            updateState { it.copy(dialog = ExpenseActionDialog.EXIT) }
        else {
            operation?.cancel()
            reads?.cancel()
            updateState { it.copy(closing = true) }
            viewModelScope.launch { emitEffect(ExpenseActionEffect.Back) }
        }
    }

    private fun onExitConfirmed(event: ExpenseActionEvent.ExitConfirmed) {
        if (state.value.dialog == ExpenseActionDialog.EXIT && !state.value.closing) {
            operation?.cancel()
            reads?.cancel()
            updateState { it.copy(closing = true) }
            viewModelScope.launch { emitEffect(ExpenseActionEffect.Back) }
        }
    }

    private fun onVerificationRequested(event: ExpenseActionEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(ExpenseActionEffect.VerifyAccount) }
    }
}
