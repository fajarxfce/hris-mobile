package dev.fajar.starter.expenses.presentation.item

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentRevisionStatus
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.usecases.SyncExpenses
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.expenses.domain.working.policies.expenseFailureRestrictsWorkingCopy
import dev.fajar.starter.expenses.domain.working.usecases.*
import dev.fajar.starter.expenses.presentation.receipts.ExpenseReceiptTarget
import dev.fajar.starter.expenses.presentation.working.*
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.coroutines.*
import kotlinx.datetime.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ExpenseItemViewModel(
    @InjectedParam private val target: ExpenseItemTarget,
    private val loadReferences: LoadExpenseDraftReferences,
    private val loadCategories: LoadExpenseCategories,
    private val loadCostCenters: LoadExpenseCostCenters,
    private val prepareLine: PrepareExpenseLine,
    private val observeWorkingCopy: ObserveExpenseWorkingCopy,
    saveWorkingCopy: SaveExpenseWorkingCopy,
    private val synchronize: SyncExpenses,
    @InjectedParam private val clock: Clock,
) :
    ExpenseWorkingCopyViewModel<ExpenseItemState, ExpenseItemEvent, ExpenseItemEffect>(
        ExpenseItemState(
            target.initialForm(),
            target.initialForm(),
            persistence = ExpenseEditPersistence(target.copy),
        ),
        target.workspaceId,
        target.slot,
        saveWorkingCopy,
    ) {
    private var admission: Job? = null
    private var choices: Job? = null
    private var expiration: Job? = null
    private var finishing: Job? = null
    private var references: Job? = null

    override fun withPersistence(state: ExpenseItemState, value: ExpenseEditPersistence?) =
        state.copy(persistence = value)

    override fun onLocalAccessFailure(failure: Failure) = reportFailure(failure)

    override suspend fun onLocalCopySaved(
        copy: ExpenseWorkingCopy,
        mutation: ExpenseWorkingMutation,
    ) {
        if (copy.item != null || state.value.persistence?.saved != true || state.value.closing)
            return
        val line = copy.input.lines.singleOrNull { it.id == target.copy.item?.input?.id } ?: return
        updateState { it.copy(closing = true) }
        emitEffect(
            ExpenseItemEffect.Saved(
                ExpenseItemResult(
                    line,
                    copy.references?.categories?.find { it.id == line.categoryId },
                    copy.references?.costCenters?.find { it.id == line.costCenterId },
                    copy.references?.receipts.orEmpty().filter { it.revisionId in line.receiptIds },
                    copy,
                )
            )
        )
    }

    init {
        on<ExpenseItemEvent.Entered>(::onEntered)
        on<ExpenseItemEvent.Stopped>(::onStopped)
        on<ExpenseItemEvent.ReloadRequested>(::onReloadRequested)
        on<ExpenseItemEvent.RetryLocalSave>(::onRetryLocalSave)
        on<ExpenseItemEvent.DateOpened>(::onDateOpened)
        on<ExpenseItemEvent.DateMonthChanged>(::onDateMonthChanged)
        on<ExpenseItemEvent.DateSelected>(::onDateSelected)
        on<ExpenseItemEvent.CategoryOpened>(::onCategoryOpened)
        on<ExpenseItemEvent.CategorySelected>(::onCategorySelected)
        on<ExpenseItemEvent.CategoryPageRequested>(::onCategoryPageRequested)
        on<ExpenseItemEvent.CategoriesExpired>(::onCategoriesExpired)
        on<ExpenseItemEvent.CostCenterOpened>(::onCostCenterOpened)
        on<ExpenseItemEvent.CostCenterQueryChanged>(::onCostCenterQueryChanged)
        on<ExpenseItemEvent.CostCenterSelected>(::onCostCenterSelected)
        on<ExpenseItemEvent.CostCenterPageRequested>(::onCostCenterPageRequested)
        on<ExpenseItemEvent.CostCentersExpired>(::onCostCentersExpired)
        on<ExpenseItemEvent.AmountChanged>(::onAmountChanged)
        on<ExpenseItemEvent.DescriptionChanged>(::onDescriptionChanged)
        on<ExpenseItemEvent.ReceiptOpened>(::onReceiptOpened)
        on<ExpenseItemEvent.ReceiptSelected>(::onReceiptSelected)
        on<ExpenseItemEvent.ReceiptDismissed>(::onReceiptDismissed)
        on<ExpenseItemEvent.ReceiptRemoved>(::onReceiptRemoved)
        on<ExpenseItemEvent.SaveRequested>(::onSaveRequested)
        on<ExpenseItemEvent.DialogDismissed>(::onDialogDismissed)
        on<ExpenseItemEvent.BackRequested>(::onBackRequested)
        on<ExpenseItemEvent.ExitConfirmed>(::onExitConfirmed)
        on<ExpenseItemEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: ExpenseItemEvent.Entered) {
        if (state.value.active || state.value.closing) return
        updateState { it.copy(active = true) }
        acquireAdmission()
    }

    private fun onStopped(event: ExpenseItemEvent.Stopped) {
        admission?.cancel()
        admission = null
        choices?.cancel()
        choices = null
        expiration?.cancel()
        expiration = null
        references?.cancel()
        references = null
        finishing?.cancel()
        finishing = null
        persistEdits(immediate = true)
        updateState {
            it.copy(
                active = false,
                ready = false,
                finishing = false,
                loading = false,
                choicesLoading = false,
                categories = null,
                costCenters = null,
            )
        }
    }

    private fun acquireAdmission() {
        admission?.cancel()
        updateState { it.copy(loading = !it.ready) }
        admission =
            viewModelScope.launch {
                try {
                    observeWorkingCopy(target.workspaceId, target.slot).collect { result ->
                        ensureActive()
                        when (result) {
                            is AppResult.Failed -> reportFailure(result.failure)
                            is AppResult.Success -> {
                                val copy = result.value
                                val local = state.value.persistence ?: return@collect
                                if (copy == null || copy.claimId != target.copy.claimId) {
                                    reportLocalFailure(
                                        expenseFailure(
                                                "client.expense_working_copy_changed",
                                                FailureKind.Conflict,
                                            )
                                            .failure
                                    )
                                    return@collect
                                }
                                val own =
                                    copy.revision == local.stored.revision ||
                                        copy.revision == local.pending?.updated?.revision
                                if (
                                    !own &&
                                        (!local.saved ||
                                            copy.item?.input?.id != target.copy.item?.input?.id)
                                ) {
                                    reportLocalFailure(
                                        expenseFailure(
                                                "client.expense_working_copy_changed",
                                                FailureKind.Conflict,
                                            )
                                            .failure
                                    )
                                    return@collect
                                }
                                val entering = !state.value.ready
                                updateState {
                                    it.copy(
                                        ready = true,
                                        loading = false,
                                        restriction = null,
                                        form =
                                            if (own) it.form
                                            else requireNotNull(copy.item).toForm(),
                                        persistence =
                                            if (own) it.persistence
                                            else ExpenseEditPersistence(copy),
                                    )
                                }
                                if (entering) {
                                    persistEdits()
                                    if (state.value.dialog == ExpenseItemDialog.Categories)
                                        acquireCategories(null)
                                    else if (state.value.dialog == ExpenseItemDialog.CostCenters)
                                        acquireCostCenters(null)
                                }
                            }
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(loading = false) }
                }
            }
    }

    private fun onReloadRequested(event: ExpenseItemEvent.ReloadRequested) {
        if (
            !state.value.active ||
                state.value.loading ||
                state.value.choicesLoading ||
                state.value.closing
        )
            return
        if (state.value.restriction != null) {
            references?.cancel()
            updateState { it.copy(loading = true) }
            references =
                viewModelScope.launch {
                    try {
                        val result = synchronize(target.workspaceId)
                        ensureActive()
                        when (result) {
                            is AppResult.Failed -> reportFailure(result.failure)
                            is AppResult.Success -> acquireAdmission()
                        }
                    } finally {
                        if (isActive) updateState { it.copy(loading = false) }
                    }
                }
        } else if (!state.value.ready) acquireAdmission()
        else
            when (state.value.dialog) {
                ExpenseItemDialog.Categories -> acquireCategories(state.value.categories?.after)
                ExpenseItemDialog.CostCenters -> acquireCostCenters(state.value.costCenters?.after)
                else -> acquireAdmission()
            }
        if (state.value.dialog == null && state.value.ready && target.line != null)
            refreshReferences()
    }

    private fun onDateOpened(event: ExpenseItemEvent.DateOpened) {
        if (!state.value.canSave) return
        val date = state.value.form.date
        updateState {
            it.copy(dialog = ExpenseItemDialog.Date(LocalDate(date.year, date.month, 1)))
        }
    }

    private fun onDateMonthChanged(event: ExpenseItemEvent.DateMonthChanged) {
        val dialog = state.value.dialog as? ExpenseItemDialog.Date ?: return
        if (!state.value.editable || event.delta !in setOf(-1, 1)) return
        val next = dialog.month.plus(event.delta, DateTimeUnit.MONTH)
        if (next.year in 1900..2200) updateState { it.copy(dialog = ExpenseItemDialog.Date(next)) }
    }

    private fun onDateSelected(event: ExpenseItemEvent.DateSelected) {
        val dialog = state.value.dialog as? ExpenseItemDialog.Date ?: return
        if (
            !state.value.editable ||
                event.day !in 1..dialog.month.daysUntil(dialog.month.plus(1, DateTimeUnit.MONTH))
        )
            return
        val date = LocalDate(dialog.month.year, dialog.month.month, event.day)
        updateState {
            it.copy(
                dialog = null,
                form =
                    if (it.form.date == date) it.form
                    else it.form.copy(date = date, category = null),
                categoryPolicy = null,
                failure = null,
            )
        }
        persistForm()
    }

    private fun onCategoryOpened(event: ExpenseItemEvent.CategoryOpened) {
        if (!state.value.canSave) return
        updateState { it.copy(dialog = ExpenseItemDialog.Categories) }
        acquireCategories(null)
    }

    private fun acquireCategories(after: String?) {
        choices?.cancel()
        expiration?.cancel()
        val date = state.value.form.date
        updateState { it.copy(choicesLoading = true, categories = null, failure = null) }
        choices =
            viewModelScope.launch {
                try {
                    val result = loadCategories(target.workspaceId, date, after)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState { it.copy(categories = result.value) }
                            expiration =
                                viewModelScope.launch {
                                    delay(
                                        (result.value.expiresAt - clock.now()).coerceIn(
                                            Duration.ZERO,
                                            EXPENSE_PREPARATION_PERIOD,
                                        )
                                    )
                                    onEvent(ExpenseItemEvent.CategoriesExpired(result.value))
                                }
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(choicesLoading = false) }
                }
            }
    }

    private fun onCategorySelected(event: ExpenseItemEvent.CategorySelected) {
        val page = state.value.categories ?: return
        if (
            !state.value.editable ||
                state.value.choicesLoading ||
                state.value.dialog != ExpenseItemDialog.Categories
        )
            return
        if (page.expiresAt <= clock.now()) {
            onEvent(ExpenseItemEvent.CategoriesExpired(page))
            return
        }
        val value = page.page.items.singleOrNull { it.id == event.id && it.active } ?: return
        expiration?.cancel()
        updateState {
            it.copy(
                form =
                    it.form.copy(
                        category =
                            ExpenseLabelReference(value.id, value.code, value.name, value.active)
                    ),
                categoryPolicy = value,
                dialog = null,
                categories = null,
                failure = null,
            )
        }
        persistForm()
    }

    private fun onCategoryPageRequested(event: ExpenseItemEvent.CategoryPageRequested) {
        val current = state.value.categories ?: return
        if (
            !state.value.editable ||
                state.value.choicesLoading ||
                state.value.dialog != ExpenseItemDialog.Categories ||
                event.after == current.after ||
                event.after != null && event.after != current.page.nextCursor
        )
            return
        acquireCategories(event.after)
    }

    private fun onCategoriesExpired(event: ExpenseItemEvent.CategoriesExpired) {
        if (state.value.categories == event.value)
            updateState {
                it.copy(
                    categories = null,
                    failure = expenseFailure("client.expense_preparation_expired").failure,
                )
            }
    }

    private fun onCostCenterOpened(event: ExpenseItemEvent.CostCenterOpened) {
        if (!state.value.canSave) return
        updateState { it.copy(dialog = ExpenseItemDialog.CostCenters, centerQuery = "") }
        acquireCostCenters(null)
    }

    private fun onCostCenterQueryChanged(event: ExpenseItemEvent.CostCenterQueryChanged) {
        if (!state.value.editable || state.value.dialog != ExpenseItemDialog.CostCenters) return
        updateState { it.copy(centerQuery = event.value.take(121)) }
        acquireCostCenters(null, debounce = true)
    }

    private fun acquireCostCenters(after: String?, debounce: Boolean = false) {
        choices?.cancel()
        expiration?.cancel()
        val query = state.value.centerQuery
        updateState { it.copy(choicesLoading = true, costCenters = null, failure = null) }
        choices =
            viewModelScope.launch {
                try {
                    if (debounce) delay(300)
                    val result = loadCostCenters(target.workspaceId, query, after)
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> reportFailure(result.failure)
                        is AppResult.Success -> {
                            updateState { it.copy(costCenters = result.value) }
                            expiration =
                                viewModelScope.launch {
                                    delay(
                                        (result.value.expiresAt - clock.now()).coerceIn(
                                            Duration.ZERO,
                                            EXPENSE_PREPARATION_PERIOD,
                                        )
                                    )
                                    onEvent(ExpenseItemEvent.CostCentersExpired(result.value))
                                }
                        }
                    }
                } finally {
                    if (isActive) updateState { it.copy(choicesLoading = false) }
                }
            }
    }

    private fun onCostCenterSelected(event: ExpenseItemEvent.CostCenterSelected) {
        if (
            !state.value.editable ||
                state.value.choicesLoading ||
                state.value.dialog != ExpenseItemDialog.CostCenters
        )
            return
        if (event.id == null) {
            choices?.cancel()
            expiration?.cancel()
            updateState {
                it.copy(form = it.form.copy(costCenter = null), dialog = null, costCenters = null)
            }
            persistForm()
            return
        }
        val page = state.value.costCenters ?: return
        if (page.expiresAt <= clock.now()) {
            onEvent(ExpenseItemEvent.CostCentersExpired(page))
            return
        }
        val value = page.page.items.singleOrNull { it.id == event.id } ?: return
        expiration?.cancel()
        updateState {
            it.copy(
                form =
                    it.form.copy(
                        costCenter = ExpenseLabelReference(value.id, value.code, value.name, true)
                    ),
                dialog = null,
                costCenters = null,
                failure = null,
            )
        }
        persistForm()
    }

    private fun onCostCenterPageRequested(event: ExpenseItemEvent.CostCenterPageRequested) {
        val current = state.value.costCenters ?: return
        if (
            !state.value.editable ||
                state.value.choicesLoading ||
                state.value.dialog != ExpenseItemDialog.CostCenters ||
                event.after == current.after ||
                event.after != null && event.after != current.page.nextCursor
        )
            return
        acquireCostCenters(event.after)
    }

    private fun onCostCentersExpired(event: ExpenseItemEvent.CostCentersExpired) {
        if (state.value.costCenters == event.value)
            updateState {
                it.copy(
                    costCenters = null,
                    failure = expenseFailure("client.expense_preparation_expired").failure,
                )
            }
    }

    private fun onAmountChanged(event: ExpenseItemEvent.AmountChanged) {
        if (state.value.canSave)
            updateState {
                it.copy(form = it.form.copy(amount = event.value.take(65)), failure = null)
            }
        persistForm()
    }

    private fun onDescriptionChanged(event: ExpenseItemEvent.DescriptionChanged) {
        if (state.value.canSave)
            updateState {
                it.copy(form = it.form.copy(description = event.value.take(501)), failure = null)
            }
        persistForm()
    }

    private fun onReceiptOpened(event: ExpenseItemEvent.ReceiptOpened) {
        if (!state.value.canSave || state.value.form.receipts.size >= 3) return
        updateState {
            it.copy(
                evidenceTarget =
                    ExpenseReceiptTarget(
                        target.workspaceId,
                        target.copy.employee.id,
                        it.form.receipts.mapNotNull { receipt -> receipt.documentId }.toSet(),
                    )
            )
        }
    }

    private fun onReceiptSelected(event: ExpenseItemEvent.ReceiptSelected) {
        if (
            state.value.evidenceTarget !== event.target ||
                state.value.form.receipts.size >= 3 ||
                state.value.form.receipts.any {
                    it.revisionId == event.value.revisionId ||
                        it.documentId == event.value.documentId
                }
        )
            return
        val value = event.value
        updateState {
            it.copy(
                form =
                    it.form.copy(
                        receipts =
                            it.form.receipts +
                                ExpenseReceiptReference(
                                    value.revisionId,
                                    value.documentId,
                                    value.fileName,
                                    value.mediaType,
                                    value.size,
                                    DocumentRevisionStatus.READY,
                                )
                    ),
                evidenceTarget = null,
                failure = null,
            )
        }
        persistForm()
    }

    private fun onReceiptDismissed(event: ExpenseItemEvent.ReceiptDismissed) {
        if (state.value.evidenceTarget === event.target)
            updateState { it.copy(evidenceTarget = null) }
    }

    private fun onReceiptRemoved(event: ExpenseItemEvent.ReceiptRemoved) {
        if (state.value.canSave)
            updateState {
                it.copy(
                    form =
                        it.form.copy(
                            receipts =
                                it.form.receipts.filterNot { receipt ->
                                    receipt.revisionId == event.id
                                }
                        ),
                    failure = null,
                )
            }
        persistForm()
    }

    private fun onSaveRequested(event: ExpenseItemEvent.SaveRequested) {
        if (!state.value.canSave) return
        val form = state.value.form
        val result = prepareLine(form.toInput(target.copy.item?.input?.id))
        when (result) {
            is AppResult.Failed -> reportFailure(result.failure)
            is AppResult.Success -> {
                updateState { it.copy(finishing = true) }
                finishing =
                    viewModelScope.launch {
                        try {
                            if (!flushLocalEdits()) return@launch
                            val local = state.value.persistence ?: return@launch
                            val line = result.value
                            val lines =
                                if (local.edited.input.lines.any { it.id == line.id })
                                    local.edited.input.lines.map {
                                        if (it.id == line.id) line else it
                                    }
                                else local.edited.input.lines + line
                            val labels =
                                expenseWorkingReferences(
                                    local.edited.employee.id,
                                    lines,
                                    local.edited.references,
                                    form.category,
                                    form.costCenter,
                                    form.receipts,
                                )
                            updateState {
                                it.copy(
                                    persistence =
                                        local.edit(
                                            local.edited.copy(
                                                input = local.edited.input.copy(lines = lines),
                                                item = null,
                                                references = labels,
                                            )
                                        )
                                )
                            }
                            flushLocalEdits()
                        } finally {
                            if (isActive) updateState { it.copy(finishing = false) }
                        }
                    }
            }
        }
    }

    private fun refreshReferences() {
        val line = target.line ?: return
        references?.cancel()
        references =
            viewModelScope.launch {
                val result =
                    loadReferences(target.workspaceId, target.copy.employee.id, listOf(line))
                ensureActive()
                when (result) {
                    is AppResult.Failed -> reportFailure(result.failure)
                    is AppResult.Success -> {
                        val values = result.value.values
                        updateState { state ->
                            state.copy(
                                form =
                                    state.form.copy(
                                        category =
                                            values.categories.find {
                                                it.id == state.form.category?.id
                                            } ?: state.form.category,
                                        costCenter =
                                            values.costCenters.find {
                                                it.id == state.form.costCenter?.id
                                            } ?: state.form.costCenter,
                                        receipts =
                                            state.form.receipts.map { old ->
                                                values.receipts.find {
                                                    it.revisionId == old.revisionId
                                                } ?: old
                                            },
                                    )
                            )
                        }
                        persistForm()
                    }
                }
            }
    }

    private fun persistForm() {
        val local = state.value.persistence ?: return
        val item = local.edited.item ?: return
        if (!state.value.ready || state.value.closing || state.value.restriction != null) return
        val form = state.value.form
        val updated =
            ExpenseWorkingItem(
                form.toInput(item.input.id).copy(amount = form.amount),
                form.category,
                form.costCenter,
                form.receipts,
            )
        updateState { it.copy(persistence = local.edit(local.edited.copy(item = updated))) }
        persistEdits()
    }

    private fun onRetryLocalSave(event: ExpenseItemEvent.RetryLocalSave) {
        if (
            !state.value.active ||
                state.value.finishing ||
                state.value.restriction != null ||
                state.value.persistence?.failure?.code == "client.expense_working_copy_changed"
        )
            return
        updateState { it.copy(persistence = it.persistence?.copy(failure = null)) }
        persistEdits(immediate = true)
    }

    private fun onDialogDismissed(event: ExpenseItemEvent.DialogDismissed) {
        choices?.cancel()
        expiration?.cancel()
        updateState {
            it.copy(dialog = null, categories = null, costCenters = null, choicesLoading = false)
        }
    }

    private fun onBackRequested(event: ExpenseItemEvent.BackRequested) {
        if (state.value.closing) return
        if (state.value.evidenceTarget != null) updateState { it.copy(evidenceTarget = null) }
        else if (state.value.dialog != null) onDialogDismissed(ExpenseItemEvent.DialogDismissed)
        else {
            finishing?.cancel()
            choices?.cancel()
            expiration?.cancel()
            updateState { it.copy(finishing = true) }
            finishing =
                viewModelScope.launch {
                    try {
                        if (state.value.restriction == null && !flushLocalEdits()) {
                            updateState { it.copy(dialog = ExpenseItemDialog.Exit) }
                            return@launch
                        }
                        if (!state.value.closing) {
                            updateState { it.copy(closing = true) }
                            emitEffect(ExpenseItemEffect.Back)
                        }
                    } finally {
                        if (isActive) updateState { it.copy(finishing = false) }
                    }
                }
        }
    }

    private fun onExitConfirmed(event: ExpenseItemEvent.ExitConfirmed) {
        if (state.value.dialog == ExpenseItemDialog.Exit && !state.value.closing) {
            cancelLocalSave()
            updateState { it.copy(closing = true) }
            viewModelScope.launch { emitEffect(ExpenseItemEffect.Back) }
        }
    }

    private fun onVerificationRequested(event: ExpenseItemEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(ExpenseItemEffect.VerifyAccount) }
    }

    private fun reportFailure(failure: Failure) {
        val denied = expenseFailureRestrictsWorkingCopy(failure)
        if (denied) {
            choices?.cancel()
            expiration?.cancel()
            cancelLocalSave()
            references?.cancel()
            finishing?.cancel()
        }
        updateState {
            if (denied)
                it.copy(
                    restriction = failure,
                    categories = null,
                    costCenters = null,
                    dialog = null,
                    evidenceTarget = null,
                    choicesLoading = false,
                    ready = false,
                    finishing = false,
                    persistence = it.persistence?.copy(writing = false),
                )
            else it.copy(failure = failure)
        }
        if (failure.code in workspaceAccessLossCodes && !state.value.needsVerification)
            viewModelScope.launch { emitEffect(ExpenseItemEffect.RecheckWorkspace) }
    }
}
