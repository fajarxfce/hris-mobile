package dev.fajar.starter.expenses.presentation.draft

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.usecases.LoadExpenseClaim
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.expenses.presentation.commands.*
import dev.fajar.starter.expenses.presentation.item.ExpenseItemTarget
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ExpenseDraftViewModel(
    @InjectedParam private val target: ExpenseDraftTarget,
    private val loadPreparation: LoadExpenseDraftPreparation,
    private val loadReferences: LoadExpenseDraftReferences,
    private val observeCommand: ObserveExpenseCommand,
    private val prepareDraft: PrepareExpenseDraft,
    private val replay: ReplayExpenseCommands,
    private val review: ReviewExpenseCommand,
    private val refreshClaim: LoadExpenseClaim,
) :
    MviViewModel<ExpenseDraftState, ExpenseDraftEvent, ExpenseDraftEffect>(
        ExpenseDraftState(editing = target.claimId != null)
    ) {
    private var observation: Job? = null
    private var preparationJob: Job? = null
    private var referenceJob: Job? = null
    private var delivery: Job? = null

    init {
        on<ExpenseDraftEvent.Entered>(::onEntered)
        on<ExpenseDraftEvent.Stopped>(::onStopped)
        on<ExpenseDraftEvent.ReloadRequested>(::onReloadRequested)
        on<ExpenseDraftEvent.ReloadConfirmed>(::onReloadConfirmed)
        on<ExpenseDraftEvent.TitleChanged>(::onTitleChanged)
        on<ExpenseDraftEvent.DescriptionChanged>(::onDescriptionChanged)
        on<ExpenseDraftEvent.ReasonChanged>(::onReasonChanged)
        on<ExpenseDraftEvent.ItemAdded>(::onItemAdded)
        on<ExpenseDraftEvent.ItemEdited>(::onItemEdited)
        on<ExpenseDraftEvent.ItemSaved>(::onItemSaved)
        on<ExpenseDraftEvent.ItemDismissed>(::onItemDismissed)
        on<ExpenseDraftEvent.ItemRemoved>(::onItemRemoved)
        on<ExpenseDraftEvent.SaveRequested>(::onSaveRequested)
        on<ExpenseDraftEvent.RetryRequested>(::onRetryRequested)
        on<ExpenseDraftEvent.OpenClaimRequested>(::onOpenClaimRequested)
        on<ExpenseDraftEvent.DiscardCommandRequested>(::onDiscardCommandRequested)
        on<ExpenseDraftEvent.DiscardCommandConfirmed>(::onDiscardCommandConfirmed)
        on<ExpenseDraftEvent.DialogDismissed>(::onDialogDismissed)
        on<ExpenseDraftEvent.BackRequested>(::onBackRequested)
        on<ExpenseDraftEvent.ExitConfirmed>(::onExitConfirmed)
        on<ExpenseDraftEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: ExpenseDraftEvent.Entered) {
        if (state.value.active || state.value.closing) return
        updateState { it.copy(active = true, commandReady = false, loading = true) }
        observeRetainedCommand()
    }

    private fun observeRetainedCommand() {
        observation?.cancel()
        val slot =
            target.claimId?.let { ExpenseCommandSlot.Claim(it) }
                ?: ExpenseCommandSlot.Creation(target.employeeId)
        observation =
            viewModelScope.launch {
                observeCommand(target.workspaceId, slot).collect { result ->
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> {
                            updateState { it.copy(commandReady = false, loading = false) }
                            reportFailure(result.failure)
                        }
                        is AppResult.Success -> {
                            val first = !state.value.commandReady
                            updateState {
                                it.copy(
                                    command = result.value,
                                    commandReady = true,
                                    dialog =
                                        it.dialog.takeUnless { dialog ->
                                            dialog == ExpenseDraftDialog.DISCARD_COMMAND &&
                                                result.value?.canDiscard() != true
                                        },
                                )
                            }
                            if (first) acquirePreparation()
                        }
                    }
                }
            }
    }

    private fun onStopped(event: ExpenseDraftEvent.Stopped) {
        observation?.cancel()
        observation = null
        preparationJob?.cancel()
        preparationJob = null
        referenceJob?.cancel()
        referenceJob = null
        delivery?.cancel()
        delivery = null
        updateState {
            it.copy(
                active = false,
                commandReady = false,
                preparation = null,
                references = null,
                command = null,
                loading = false,
                work = null,
                dialog = null,
            )
        }
    }

    private fun onReloadRequested(event: ExpenseDraftEvent.ReloadRequested) {
        if (
            !state.value.active ||
                state.value.work != null ||
                state.value.loading ||
                state.value.itemTarget != null
        )
            return
        if (
            state.value.dirty &&
                state.value.remoteChanged &&
                state.value.command == null &&
                state.value.restriction == null
        )
            updateState { it.copy(dialog = ExpenseDraftDialog.RELOAD) }
        else if (!state.value.commandReady) observeRetainedCommand() else acquirePreparation()
    }

    private fun onReloadConfirmed(event: ExpenseDraftEvent.ReloadConfirmed) {
        if (state.value.dialog != ExpenseDraftDialog.RELOAD || state.value.work != null) return
        updateState {
            it.copy(
                form = null,
                original = null,
                version = null,
                remoteChanged = false,
                dialog = null,
                references = null,
            )
        }
        acquirePreparation()
    }

    private fun acquirePreparation() {
        preparationJob?.cancel()
        referenceJob?.cancel()
        updateState { it.copy(loading = true) }
        preparationJob =
            viewModelScope.launch {
                try {
                    val result =
                        loadPreparation(
                            target.workspaceId,
                            target.employeeId,
                            target.claimId.takeIf { state.value.command == null },
                            target.employmentAfter,
                        )
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState { it.withPreparation(result.value) }
                            acquireReferences()
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(loading = false) }
                }
            }
    }

    private fun acquireReferences() {
        referenceJob?.cancel()
        val lines = state.value.form?.lines ?: return
        if (lines.isEmpty() || state.value.command != null) return
        referenceJob =
            viewModelScope.launch {
                val result = loadReferences(target.workspaceId, target.employeeId, lines)
                ensureActive()
                if (state.value.form?.lines != lines || state.value.command != null) return@launch
                when (result) {
                    is AppResult.Failed -> reportFailure(result.failure)
                    is AppResult.Success ->
                        updateState { it.copy(references = result.value.values) }
                }
            }
    }

    private fun onTitleChanged(event: ExpenseDraftEvent.TitleChanged) {
        if (state.value.editable && state.value.itemTarget == null)
            updateState {
                it.copy(form = it.form?.copy(title = event.value.take(161)), failure = null)
            }
    }

    private fun onDescriptionChanged(event: ExpenseDraftEvent.DescriptionChanged) {
        if (state.value.editable && state.value.itemTarget == null)
            updateState {
                it.copy(form = it.form?.copy(description = event.value.take(2_001)), failure = null)
            }
    }

    private fun onReasonChanged(event: ExpenseDraftEvent.ReasonChanged) {
        if (state.value.editable && state.value.itemTarget == null)
            updateState {
                it.copy(form = it.form?.copy(reason = event.value.take(1_001)), failure = null)
            }
    }

    private fun onItemAdded(event: ExpenseDraftEvent.ItemAdded) {
        val prepared = state.value.preparation ?: return
        if (
            !state.value.editable ||
                state.value.itemTarget != null ||
                state.value.form?.lines.orEmpty().size >= 20
        )
            return
        updateState {
            it.copy(
                itemTarget = ExpenseItemTarget(target.workspaceId, prepared, null, it.references)
            )
        }
    }

    private fun onItemEdited(event: ExpenseDraftEvent.ItemEdited) {
        val prepared = state.value.preparation ?: return
        val line = state.value.form?.lines?.singleOrNull { it.id == event.id } ?: return
        if (!state.value.editable || state.value.itemTarget != null) return
        updateState {
            it.copy(
                itemTarget = ExpenseItemTarget(target.workspaceId, prepared, line, it.references)
            )
        }
    }

    private fun onItemSaved(event: ExpenseDraftEvent.ItemSaved) {
        if (state.value.itemTarget !== event.target) return
        if (event.target.line != null && event.target.line.id != event.value.line.id) return
        if (event.target.line == null && state.value.form?.lines.orEmpty().size >= 20) return
        referenceJob?.cancel()
        updateState { it.withItem(event.value) }
    }

    private fun onItemDismissed(event: ExpenseDraftEvent.ItemDismissed) {
        if (state.value.itemTarget === event.target) updateState { it.copy(itemTarget = null) }
    }

    private fun onItemRemoved(event: ExpenseDraftEvent.ItemRemoved) {
        if (!state.value.editable || state.value.itemTarget != null) return
        referenceJob?.cancel()
        updateState {
            it.copy(
                form =
                    it.form?.copy(lines = it.form.lines.filterNot { line -> line.id == event.id }),
                failure = null,
            )
        }
    }

    private fun onSaveRequested(event: ExpenseDraftEvent.SaveRequested) {
        if (!state.value.canSave) return
        val form = state.value.form ?: return
        val version = state.value.version
        referenceJob?.cancel()
        updateState { it.copy(work = ExpenseDraftWork.SAVING, failure = null) }
        delivery =
            viewModelScope.launch {
                try {
                    val preparation =
                        when (
                            val result =
                                loadPreparation(
                                    target.workspaceId,
                                    target.employeeId,
                                    target.claimId,
                                    target.employmentAfter,
                                )
                        ) {
                            is AppResult.Failed -> {
                                ensureActive()
                                reportFailure(result.failure)
                                return@launch
                            }
                            is AppResult.Success -> result.value
                        }
                    ensureActive()
                    val result =
                        prepareDraft(
                            target.workspaceId,
                            preparation,
                            ExpenseDraftInput(
                                form.title,
                                form.description,
                                form.lines,
                                form.reason,
                            ),
                            expectedVersion = version,
                        )
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState {
                                it.copy(command = result.value, work = ExpenseDraftWork.SENDING)
                            }
                            sendCommand(result.value.command.id)
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun onRetryRequested(event: ExpenseDraftEvent.RetryRequested) {
        val record = state.value.command ?: return
        if (
            !state.value.active ||
                !state.value.commandReady ||
                state.value.work != null ||
                state.value.restriction != null ||
                !record.canRetry()
        )
            return
        updateState { it.copy(work = ExpenseDraftWork.SENDING, failure = null) }
        delivery =
            viewModelScope.launch {
                try {
                    sendCommand(record.command.id)
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private suspend fun sendCommand(id: String) {
        val result = replay(target.workspaceId, id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Failed -> reportFailure(result.failure)
            is AppResult.Success -> {
                updateState { it.copy(command = result.value) }
                when (val outcome = result.value.outcome) {
                    is ExpenseCommandOutcome.Confirmed -> openConfirmedClaim(result.value)
                    is ExpenseCommandOutcome.Rejected -> reportFailure(outcome.failure)
                    is ExpenseCommandOutcome.Unconfirmed -> outcome.failure?.let(::reportFailure)
                    ExpenseCommandOutcome.Pending -> Unit
                }
            }
        }
    }

    private fun onOpenClaimRequested(event: ExpenseDraftEvent.OpenClaimRequested) {
        val record = state.value.command ?: return
        if (
            !state.value.active ||
                !state.value.commandReady ||
                state.value.work != null ||
                state.value.restriction != null ||
                record.outcome !is ExpenseCommandOutcome.Confirmed
        )
            return
        updateState { it.copy(work = ExpenseDraftWork.OPENING, failure = null) }
        delivery =
            viewModelScope.launch {
                try {
                    openConfirmedClaim(record)
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private suspend fun openConfirmedClaim(record: ExpenseCommandRecord) {
        val refreshed = refreshClaim(target.workspaceId, record.command.claimId)
        currentCoroutineContext().ensureActive()
        if (refreshed is AppResult.Failed) {
            reportFailure(refreshed.failure)
            return
        }
        val reviewed = review(target.workspaceId, record.command.id)
        currentCoroutineContext().ensureActive()
        when (reviewed) {
            is AppResult.Failed -> reportFailure(reviewed.failure)
            is AppResult.Success -> {
                if (!state.value.closing) {
                    updateState { it.copy(closing = true) }
                    emitEffect(
                        ExpenseDraftEffect.OpenClaim(
                            record.command.claimId,
                            record.command.employmentAfter,
                        )
                    )
                }
            }
        }
    }

    private fun onDiscardCommandRequested(event: ExpenseDraftEvent.DiscardCommandRequested) {
        if (
            state.value.active &&
                state.value.work == null &&
                state.value.restriction == null &&
                state.value.command?.canDiscard() == true
        )
            updateState { it.copy(dialog = ExpenseDraftDialog.DISCARD_COMMAND) }
    }

    private fun onDiscardCommandConfirmed(event: ExpenseDraftEvent.DiscardCommandConfirmed) {
        val record = state.value.command ?: return
        if (
            !state.value.active ||
                state.value.dialog != ExpenseDraftDialog.DISCARD_COMMAND ||
                state.value.work != null ||
                state.value.restriction != null ||
                !record.canDiscard()
        )
            return
        updateState { it.copy(dialog = null, work = ExpenseDraftWork.DISCARDING, failure = null) }
        delivery =
            viewModelScope.launch {
                try {
                    val result = review(target.workspaceId, record.command.id)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            if (result.value) {
                                val payload =
                                    record.command.payload as? ExpenseCommandPayload.SaveDraft
                                if (payload != null)
                                    updateState {
                                        it.copy(
                                            command = null,
                                            version = payload.expectedVersion,
                                            original = it.original ?: ExpenseDraftForm(),
                                            form =
                                                ExpenseDraftForm(
                                                    payload.title,
                                                    payload.description,
                                                    payload.lines,
                                                    record.command.reason,
                                                ),
                                        )
                                    }
                            }
                            acquirePreparation()
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun reportFailure(failure: Failure) {
        val denied = expenseFailureInvalidatesCache(failure)
        if (denied) {
            preparationJob?.cancel()
            referenceJob?.cancel()
            delivery?.cancel()
        }
        updateState {
            if (denied)
                it.copy(
                    restriction = failure,
                    references = null,
                    itemTarget = null,
                    loading = false,
                    work = null,
                )
            else
                it.copy(
                    failure = failure,
                    remoteChanged = it.remoteChanged || failure.code == "client.expense_changed",
                )
        }
        if (failure.code in workspaceAccessLossCodes && !state.value.needsVerification)
            viewModelScope.launch { emitEffect(ExpenseDraftEffect.RecheckWorkspace) }
    }

    private fun onDialogDismissed(event: ExpenseDraftEvent.DialogDismissed) {
        updateState { it.copy(dialog = null) }
    }

    private fun onBackRequested(event: ExpenseDraftEvent.BackRequested) {
        if (state.value.closing) return
        if (state.value.dialog != null) updateState { it.copy(dialog = null) }
        else if (state.value.command == null && state.value.dirty)
            updateState { it.copy(dialog = ExpenseDraftDialog.EXIT) }
        else {
            updateState { it.copy(closing = true) }
            viewModelScope.launch { emitEffect(ExpenseDraftEffect.Back) }
        }
    }

    private fun onExitConfirmed(event: ExpenseDraftEvent.ExitConfirmed) {
        if (state.value.dialog == ExpenseDraftDialog.EXIT && !state.value.closing) {
            updateState { it.copy(closing = true) }
            viewModelScope.launch { emitEffect(ExpenseDraftEffect.Back) }
        }
    }

    private fun onVerificationRequested(event: ExpenseDraftEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(ExpenseDraftEffect.VerifyAccount) }
    }
}
