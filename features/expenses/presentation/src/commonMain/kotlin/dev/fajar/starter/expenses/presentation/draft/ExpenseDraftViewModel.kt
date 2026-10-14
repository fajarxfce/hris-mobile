@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.presentation.draft

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.usecases.LoadExpenseClaim
import dev.fajar.starter.expenses.domain.replica.usecases.SyncExpenses
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.expenses.domain.working.policies.expenseFailureRestrictsWorkingCopy
import dev.fajar.starter.expenses.domain.working.usecases.*
import dev.fajar.starter.expenses.presentation.commands.*
import dev.fajar.starter.expenses.presentation.item.ExpenseItemTarget
import dev.fajar.starter.expenses.presentation.working.*
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.*
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
    private val openWorkingCopy: OpenExpenseWorkingCopy,
    private val observeWorkingCopy: ObserveExpenseWorkingCopy,
    saveWorkingCopy: SaveExpenseWorkingCopy,
    private val discardWorkingCopy: DiscardExpenseWorkingCopy,
    private val synchronize: SyncExpenses,
    @InjectedParam private val clock: Clock,
) :
    ExpenseWorkingCopyViewModel<ExpenseDraftState, ExpenseDraftEvent, ExpenseDraftEffect>(
        ExpenseDraftState(editing = target.claimId != null),
        target.workspaceId,
        target.claimId?.let { ExpenseWorkingSlot.Claim(it) }
            ?: ExpenseWorkingSlot.Creation(target.employeeId),
        saveWorkingCopy,
    ) {
    private var observation: Job? = null
    private var preparationJob: Job? = null
    private var referenceJob: Job? = null
    private var delivery: Job? = null
    private var localObservation: Job? = null
    private var commandSlot: ExpenseCommandSlot? = null
    private val workingSlot =
        target.claimId?.let { ExpenseWorkingSlot.Claim(it) }
            ?: ExpenseWorkingSlot.Creation(target.employeeId)

    override fun withPersistence(state: ExpenseDraftState, value: ExpenseEditPersistence?) =
        state.copy(persistence = value)

    override fun onLocalAccessFailure(failure: Failure) = reportFailure(failure)

    override suspend fun onLocalCopySaved(
        copy: ExpenseWorkingCopy,
        mutation: ExpenseWorkingMutation,
    ) {
        val prepared = mutation.reload ?: return
        if (state.value.persistence?.saved != true) return
        updateState { it.withWorkingCopy(copy).copy(preparation = prepared, remoteChanged = false) }
        observeRetainedCommand()
    }

    init {
        on<ExpenseDraftEvent.Entered>(::onEntered)
        on<ExpenseDraftEvent.Stopped>(::onStopped)
        on<ExpenseDraftEvent.ReloadRequested>(::onReloadRequested)
        on<ExpenseDraftEvent.ReloadConfirmed>(::onReloadConfirmed)
        on<ExpenseDraftEvent.TitleChanged>(::onTitleChanged)
        on<ExpenseDraftEvent.DescriptionChanged>(::onDescriptionChanged)
        on<ExpenseDraftEvent.ReasonChanged>(::onReasonChanged)
        on<ExpenseDraftEvent.ItemAdded>(::onItemAdded)
        on<ExpenseDraftEvent.ItemResumed>(::onItemResumed)
        on<ExpenseDraftEvent.ItemDiscardRequested>(::onItemDiscardRequested)
        on<ExpenseDraftEvent.ItemDiscardConfirmed>(::onItemDiscardConfirmed)
        on<ExpenseDraftEvent.RetryLocalSave>(::onRetryLocalSave)
        on<ExpenseDraftEvent.DiscardLocalRequested>(::onDiscardLocalRequested)
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
        on<ExpenseDraftEvent.ExitWithoutSaving>(::onExitWithoutSaving)
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
            (target.claimId
                    ?: state.value.persistence
                        ?.edited
                        ?.takeIf { it.expectedVersion != null }
                        ?.claimId)
                ?.let { ExpenseCommandSlot.Claim(it) }
                ?: ExpenseCommandSlot.Creation(target.employeeId)
        commandSlot = slot
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
                                    restriction = null,
                                    dialog =
                                        it.dialog.takeUnless { dialog ->
                                            dialog == ExpenseDraftDialog.DISCARD_COMMAND &&
                                                result.value?.canDiscard() != true
                                        },
                                )
                            }
                            if (first) {
                                if (result.value == null) openLocalCopy()
                                else updateState { it.copy(loading = false) }
                            }
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
        localObservation?.cancel()
        localObservation = null
        persistEdits(immediate = true)
        updateState {
            it.copy(
                active = false,
                commandReady = false,
                preparation = null,
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
        if (state.value.restriction != null) acquirePreparation()
        else if (
            state.value.command == null &&
                state.value.persistence?.failure?.code == "client.expense_working_copy_changed"
        )
            updateState { it.copy(dialog = ExpenseDraftDialog.RELOAD) }
        else if (
            state.value.remoteChanged &&
                state.value.command == null &&
                state.value.restriction == null
        )
            updateState { it.copy(dialog = ExpenseDraftDialog.RELOAD) }
        else if (!state.value.commandReady || state.value.command != null) observeRetainedCommand()
        else if (state.value.persistence == null) openLocalCopy() else acquirePreparation()
    }

    private fun onReloadConfirmed(event: ExpenseDraftEvent.ReloadConfirmed) {
        if (state.value.dialog != ExpenseDraftDialog.RELOAD || state.value.work != null) return
        val current = state.value.persistence ?: return
        cancelLocalSave()
        updateState { it.copy(dialog = null, work = ExpenseDraftWork.SAVING) }
        delivery =
            viewModelScope.launch {
                try {
                    val loaded = openWorkingCopy(target.workspaceId, target.employeeId, workingSlot)
                    ensureActive()
                    val latest =
                        when (loaded) {
                            is AppResult.Failed -> {
                                reportFailure(loaded.failure)
                                return@launch
                            }
                            is AppResult.Success -> loaded.value
                        }
                    if (current.failure?.code == "client.expense_working_copy_changed") {
                        updateState { it.withWorkingCopy(latest) }
                        observeLocalCopy()
                        return@launch
                    }
                    val result =
                        loadPreparation(
                            target.workspaceId,
                            target.employeeId,
                            latest.claimId.takeIf {
                                latest.expectedVersion != null || state.value.remoteChanged
                            },
                            target.employmentAfter,
                        )
                    ensureActive()
                    val prepared =
                        when (result) {
                            is AppResult.Failed -> {
                                reportFailure(result.failure)
                                return@launch
                            }
                            is AppResult.Success -> result.value
                        }
                    val proposal =
                        latest.copy(
                            employee = prepared.employee,
                            input =
                                ExpenseDraftInput(
                                    prepared.claim?.draft?.title.orEmpty(),
                                    prepared.claim?.draft?.description.orEmpty(),
                                    prepared.claim?.draft?.lines.orEmpty(),
                                    "",
                                ),
                            expectedVersion = prepared.claim?.version,
                            timezone = prepared.access.timezone,
                            item = null,
                            references = null,
                            revision = Uuid.random().toString(),
                        )
                    updateState {
                        it.copy(
                            persistence =
                                ExpenseEditPersistence(
                                    latest,
                                    proposal,
                                    ExpenseWorkingMutation(latest, proposal, prepared),
                                )
                        )
                    }
                    flushLocalEdits()
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun acquirePreparation() {
        preparationJob?.cancel()
        referenceJob?.cancel()
        updateState { it.copy(loading = true) }
        preparationJob =
            viewModelScope.launch {
                try {
                    if (state.value.restriction != null) {
                        val sync = synchronize(target.workspaceId)
                        ensureActive()
                        if (sync is AppResult.Failed) {
                            reportFailure(sync.failure)
                            return@launch
                        }
                        val retained =
                            withTimeoutOrNull(10_000) {
                                observeCommand(target.workspaceId, requireNotNull(commandSlot))
                                    .first()
                            } ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)
                        ensureActive()
                        when (retained) {
                            is AppResult.Failed -> {
                                reportFailure(retained.failure)
                                return@launch
                            }
                            is AppResult.Success ->
                                if (retained.value != null) {
                                    updateState {
                                        it.copy(
                                            command = retained.value,
                                            commandReady = true,
                                            restriction = null,
                                        )
                                    }
                                    return@launch
                                }
                        }
                        val opened =
                            openWorkingCopy(target.workspaceId, target.employeeId, workingSlot)
                        ensureActive()
                        when (opened) {
                            is AppResult.Failed -> {
                                reportFailure(opened.failure)
                                return@launch
                            }
                            is AppResult.Success -> acceptLocalCopy(opened.value)
                        }
                        observeLocalCopy()
                    }
                    val result =
                        loadPreparation(
                            target.workspaceId,
                            target.employeeId,
                            (target.claimId ?: state.value.persistence?.edited?.claimId).takeIf {
                                state.value.command == null &&
                                    (state.value.version != null || state.value.remoteChanged)
                            },
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

    private fun openLocalCopy() {
        preparationJob?.cancel()
        updateState { it.copy(loading = it.persistence == null) }
        preparationJob =
            viewModelScope.launch {
                try {
                    var result = openWorkingCopy(target.workspaceId, target.employeeId, workingSlot)
                    ensureActive()
                    if (
                        result is AppResult.Failed &&
                            result.failure.code == "client.expense_updates_pending" &&
                            state.value.persistence == null
                    ) {
                        val prepared =
                            loadPreparation(
                                target.workspaceId,
                                target.employeeId,
                                target.claimId,
                                target.employmentAfter,
                            )
                        ensureActive()
                        result =
                            when (prepared) {
                                is AppResult.Failed -> prepared
                                is AppResult.Success ->
                                    openWorkingCopy(
                                        target.workspaceId,
                                        target.employeeId,
                                        workingSlot,
                                        prepared.value,
                                    )
                            }
                    }
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            acceptLocalCopy(result.value)
                            val slot =
                                result.value
                                    .takeIf { it.expectedVersion != null }
                                    ?.let { ExpenseCommandSlot.Claim(it.claimId) }
                                    ?: ExpenseCommandSlot.Creation(target.employeeId)
                            if (commandSlot != slot) observeRetainedCommand() else persistEdits()
                        }
                    }
                    observeLocalCopy()
                } finally {
                    if (isActive) updateState { it.copy(loading = false) }
                }
            }
    }

    private fun observeLocalCopy() {
        localObservation?.cancel()
        localObservation =
            viewModelScope.launch {
                observeWorkingCopy(target.workspaceId, workingSlot).collect { result ->
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            val value = result.value
                            if (value != null) acceptLocalCopy(value)
                            else if (
                                state.value.persistence != null &&
                                    state.value.command == null &&
                                    state.value.work == null
                            )
                                reportLocalFailure(
                                    expenseFailure(
                                            "client.expense_working_copy_changed",
                                            FailureKind.Conflict,
                                        )
                                        .failure
                                )
                        }
                    }
                }
            }
    }

    private fun acceptLocalCopy(value: ExpenseWorkingCopy) {
        if (value.employee.id != target.employeeId) {
            reportFailure(
                expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied).failure
            )
            return
        }
        val local = state.value.persistence
        updateState { it.copy(restriction = null) }
        when {
            local == null || state.value.itemTarget != null ->
                updateState { it.withWorkingCopy(value) }
            value.revision == local.stored.revision ||
                value.revision == local.pending?.updated?.revision -> Unit
            local.saved -> updateState { it.withWorkingCopy(value) }
            else ->
                reportLocalFailure(
                    expenseFailure("client.expense_working_copy_changed", FailureKind.Conflict)
                        .failure
                )
        }
    }

    private fun persistForm() {
        val local = state.value.persistence ?: return
        val form = state.value.form ?: return
        val input = ExpenseDraftInput(form.title, form.description, form.lines, form.reason)
        val references =
            expenseWorkingReferences(local.edited.employee.id, input.lines, state.value.references)
        updateState {
            it.copy(
                persistence = local.edit(local.edited.copy(input = input, references = references)),
                references = references,
            )
        }
        persistEdits()
    }

    /**
     * Serial, coalesced writes keep an uncertain mutation until its exact acknowledgement is
     * recovered.
     */
    private fun onRetryLocalSave(event: ExpenseDraftEvent.RetryLocalSave) {
        if (
            !state.value.active ||
                state.value.work != null ||
                state.value.restriction != null ||
                state.value.persistence?.failure?.code == "client.expense_working_copy_changed"
        )
            return
        updateState { it.copy(persistence = it.persistence?.copy(failure = null)) }
        persistEdits(immediate = true)
    }

    private fun openItemAfterSaving() {
        updateState { it.copy(work = ExpenseDraftWork.OPENING_ITEM) }
        delivery =
            viewModelScope.launch {
                try {
                    if (!flushLocalEdits()) return@launch
                    val copy = state.value.persistence?.stored ?: return@launch
                    if (copy.item != null)
                        updateState {
                            it.copy(
                                itemTarget =
                                    ExpenseItemTarget(
                                        target.workspaceId,
                                        workingSlot,
                                        copy,
                                        target.employmentAfter,
                                    )
                            )
                        }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun onItemResumed(event: ExpenseDraftEvent.ItemResumed) {
        if (
            state.value.editable &&
                state.value.itemTarget == null &&
                state.value.persistence?.edited?.item != null
        )
            openItemAfterSaving()
    }

    private fun onItemDiscardRequested(event: ExpenseDraftEvent.ItemDiscardRequested) {
        if (state.value.editable && state.value.persistence?.edited?.item != null)
            updateState { it.copy(dialog = ExpenseDraftDialog.DISCARD_ITEM) }
    }

    private fun onItemDiscardConfirmed(event: ExpenseDraftEvent.ItemDiscardConfirmed) {
        if (state.value.dialog != ExpenseDraftDialog.DISCARD_ITEM || state.value.work != null)
            return
        val local = state.value.persistence ?: return
        updateState {
            it.copy(dialog = null, persistence = local.edit(local.edited.copy(item = null)))
        }
        persistEdits(immediate = true)
    }

    private fun onDiscardLocalRequested(event: ExpenseDraftEvent.DiscardLocalRequested) {
        val local = state.value.persistence ?: return
        if (state.value.editable && state.value.itemTarget == null)
            updateState {
                it.copy(dialog = ExpenseDraftDialog.EXIT, discardRevision = local.stored.revision)
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
        persistForm()
    }

    private fun onDescriptionChanged(event: ExpenseDraftEvent.DescriptionChanged) {
        if (state.value.editable && state.value.itemTarget == null)
            updateState {
                it.copy(form = it.form?.copy(description = event.value.take(2_001)), failure = null)
            }
        persistForm()
    }

    private fun onReasonChanged(event: ExpenseDraftEvent.ReasonChanged) {
        if (state.value.editable && state.value.itemTarget == null)
            updateState {
                it.copy(form = it.form?.copy(reason = event.value.take(1_001)), failure = null)
            }
        persistForm()
    }

    private fun onItemAdded(event: ExpenseDraftEvent.ItemAdded) {
        val copy = state.value.persistence?.edited ?: return
        if (
            !state.value.editable ||
                state.value.itemTarget != null ||
                copy.item != null ||
                state.value.form?.lines.orEmpty().size >= 20
        )
            return
        val date = clock.now().toLocalDateTime(TimeZone.of(copy.timezone)).date
        val item =
            ExpenseWorkingItem(
                ExpenseLineInput(Uuid.random().toString(), "", date.toString(), "", "")
            )
        updateState { it.copy(persistence = it.persistence?.edit(copy.copy(item = item))) }
        openItemAfterSaving()
    }

    private fun onItemEdited(event: ExpenseDraftEvent.ItemEdited) {
        val copy = state.value.persistence?.edited ?: return
        val line = state.value.form?.lines?.singleOrNull { it.id == event.id } ?: return
        if (!state.value.editable || state.value.itemTarget != null || copy.item != null) return
        val references = state.value.references
        val item =
            ExpenseWorkingItem(
                ExpenseLineInput(
                    line.id,
                    line.categoryId,
                    line.occurredOn.toString(),
                    line.amount.value,
                    line.description,
                    line.costCenterId,
                    line.receiptIds,
                ),
                references?.categories?.find { it.id == line.categoryId }
                    ?: ExpenseLabelReference(line.categoryId),
                line.costCenterId?.let { id ->
                    references?.costCenters?.find { it.id == id } ?: ExpenseLabelReference(id)
                },
                line.receiptIds.map { id ->
                    references?.receipts?.find { it.revisionId == id }
                        ?: ExpenseReceiptReference(id)
                },
            )
        updateState { it.copy(persistence = it.persistence?.edit(copy.copy(item = item))) }
        openItemAfterSaving()
    }

    private fun onItemSaved(event: ExpenseDraftEvent.ItemSaved) {
        if (state.value.itemTarget !== event.target) return
        if (
            event.target.copy.item?.input?.id != event.value.line.id ||
                event.value.copy.claimId != event.target.copy.claimId
        )
            return
        referenceJob?.cancel()
        updateState { it.withWorkingCopy(event.value.copy).copy(itemTarget = null) }
    }

    private fun onItemDismissed(event: ExpenseDraftEvent.ItemDismissed) {
        if (state.value.itemTarget === event.target) {
            updateState { it.copy(itemTarget = null) }
            openLocalCopy()
        }
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
        persistForm()
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
                    if (!flushLocalEdits()) return@launch
                    val copy = state.value.persistence?.stored ?: return@launch
                    val preparation =
                        when (
                            val result =
                                loadPreparation(
                                    target.workspaceId,
                                    target.employeeId,
                                    copy.claimId.takeIf { copy.expectedVersion != null },
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
                            reservedClaimId = copy.claimId,
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
                    val command =
                        record.command.takeIf { it.payload is ExpenseCommandPayload.SaveDraft }
                    var opened =
                        openWorkingCopy(
                            target.workspaceId,
                            target.employeeId,
                            workingSlot,
                            recoveredCommand = command,
                        )
                    ensureActive()
                    if (
                        opened is AppResult.Failed &&
                            opened.failure.code == "client.expense_updates_pending"
                    ) {
                        val preparation =
                            loadPreparation(
                                target.workspaceId,
                                target.employeeId,
                                target.claimId,
                                target.employmentAfter,
                            )
                        ensureActive()
                        opened =
                            when (preparation) {
                                is AppResult.Failed -> preparation
                                is AppResult.Success ->
                                    openWorkingCopy(
                                        target.workspaceId,
                                        target.employeeId,
                                        workingSlot,
                                        preparation.value,
                                        command,
                                    )
                            }
                    }
                    ensureActive()
                    val copy =
                        when (opened) {
                            is AppResult.Failed -> {
                                reportFailure(opened.failure)
                                return@launch
                            }
                            is AppResult.Success -> opened.value
                        }
                    val result =
                        review(
                            target.workspaceId,
                            record.command.id,
                            expectedRevision = record.revision,
                        )
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            if (result.value) {
                                updateState { it.withWorkingCopy(copy).copy(command = null) }
                                observeLocalCopy()
                                acquirePreparation()
                            } else observeRetainedCommand()
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun reportFailure(failure: Failure) {
        val denied = expenseFailureRestrictsWorkingCopy(failure)
        if (denied) {
            preparationJob?.cancel()
            referenceJob?.cancel()
            delivery?.cancel()
            cancelLocalSave()
        }
        updateState {
            if (denied)
                it.copy(
                    restriction = failure,
                    references = null,
                    itemTarget = null,
                    loading = false,
                    work = null,
                    persistence = it.persistence?.copy(writing = false),
                )
            else
                it.copy(
                    failure = failure,
                    remoteChanged =
                        it.remoteChanged ||
                            failure.code in setOf("client.expense_changed", "stale_version"),
                )
        }
        if (failure.code in workspaceAccessLossCodes && !state.value.needsVerification)
            viewModelScope.launch { emitEffect(ExpenseDraftEffect.RecheckWorkspace) }
    }

    private fun onDialogDismissed(event: ExpenseDraftEvent.DialogDismissed) {
        updateState { it.copy(dialog = null, discardRevision = null) }
    }

    private fun onBackRequested(event: ExpenseDraftEvent.BackRequested) {
        if (state.value.closing) return
        if (state.value.dialog != null) updateState { it.copy(dialog = null) }
        else {
            delivery?.cancel()
            preparationJob?.cancel()
            referenceJob?.cancel()
            updateState { it.copy(work = ExpenseDraftWork.LEAVING) }
            delivery =
                viewModelScope.launch {
                    try {
                        if (state.value.restriction == null && !flushLocalEdits()) {
                            updateState { it.copy(dialog = ExpenseDraftDialog.LEAVE_UNSAVED) }
                            return@launch
                        }
                        updateState { it.copy(closing = true) }
                        emitEffect(ExpenseDraftEffect.Back)
                    } finally {
                        if (isActive) updateState { it.copy(work = null) }
                    }
                }
        }
    }

    private fun onExitConfirmed(event: ExpenseDraftEvent.ExitConfirmed) {
        val revision = state.value.discardRevision ?: return
        if (
            state.value.dialog != ExpenseDraftDialog.EXIT ||
                state.value.closing ||
                state.value.work != null
        )
            return
        cancelLocalSave()
        updateState { it.copy(dialog = null, work = ExpenseDraftWork.DISCARDING) }
        delivery =
            viewModelScope.launch {
                try {
                    awaitLocalSave()
                    val result = discardWorkingCopy(target.workspaceId, workingSlot, revision)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportLocalFailure(result.failure)
                        is AppResult.Success -> {
                            updateState { it.copy(closing = true) }
                            emitEffect(ExpenseDraftEffect.Back)
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(work = null) }
                }
            }
    }

    private fun onExitWithoutSaving(event: ExpenseDraftEvent.ExitWithoutSaving) {
        if (state.value.dialog != ExpenseDraftDialog.LEAVE_UNSAVED || state.value.closing) return
        cancelLocalSave()
        updateState { it.copy(closing = true) }
        viewModelScope.launch { emitEffect(ExpenseDraftEffect.Back) }
    }

    private fun onVerificationRequested(event: ExpenseDraftEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(ExpenseDraftEffect.VerifyAccount) }
    }
}
