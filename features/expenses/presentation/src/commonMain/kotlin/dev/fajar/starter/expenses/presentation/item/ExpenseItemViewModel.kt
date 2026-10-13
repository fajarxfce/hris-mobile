package dev.fajar.starter.expenses.presentation.item

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentRevisionStatus
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.expenses.presentation.receipts.ExpenseReceiptTarget
import dev.fajar.starter.presentation.mvi.MviViewModel
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
    private val loadPreparation: LoadExpenseDraftPreparation,
    private val loadReferences: LoadExpenseDraftReferences,
    private val loadCategories: LoadExpenseCategories,
    private val loadCostCenters: LoadExpenseCostCenters,
    private val prepareLine: PrepareExpenseLine,
    @InjectedParam private val clock: Clock,
) :
    MviViewModel<ExpenseItemState, ExpenseItemEvent, ExpenseItemEffect>(
        ExpenseItemState(target.initialForm(), target.initialForm())
    ) {
    private var admission: Job? = null
    private var choices: Job? = null
    private var expiration: Job? = null

    init {
        on<ExpenseItemEvent.Entered>(::onEntered)
        on<ExpenseItemEvent.Stopped>(::onStopped)
        on<ExpenseItemEvent.ReloadRequested>(::onReloadRequested)
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
        updateState {
            it.copy(
                active = false,
                ready = false,
                loading = false,
                choicesLoading = false,
                categories = null,
                costCenters = null,
            )
        }
    }

    private fun acquireAdmission() {
        admission?.cancel()
        updateState { it.copy(loading = true) }
        admission =
            viewModelScope.launch {
                try {
                    val prepared =
                        loadPreparation(
                            target.workspaceId,
                            target.preparation.employee.id,
                            employmentAfter = target.preparation.employmentAfter,
                        )
                    ensureActive()
                    when (prepared) {
                        is AppResult.Failed -> {
                            reportFailure(prepared.failure)
                            return@launch
                        }
                        is AppResult.Success ->
                            updateState {
                                it.copy(ready = true, restriction = null, failure = null)
                            }
                    }
                    val line = target.line
                    if (line != null) {
                        val result =
                            loadReferences(
                                target.workspaceId,
                                target.preparation.employee.id,
                                listOf(line),
                            )
                        ensureActive()
                        when (result) {
                            is AppResult.Failed -> reportFailure(result.failure)
                            is AppResult.Success ->
                                updateState { state ->
                                    state.copy(
                                        form =
                                            state.form.copy(
                                                category =
                                                    result.value.values.categories.find {
                                                        it.id == state.form.category?.id
                                                    } ?: state.form.category,
                                                costCenter =
                                                    result.value.values.costCenters.find {
                                                        it.id == state.form.costCenter?.id
                                                    } ?: state.form.costCenter,
                                                receipts =
                                                    state.form.receipts.map { old ->
                                                        result.value.values.receipts.find {
                                                            it.revisionId == old.revisionId
                                                        } ?: old
                                                    },
                                            )
                                    )
                                }
                        }
                    }
                    if (state.value.dialog == ExpenseItemDialog.Categories) acquireCategories(null)
                    else if (state.value.dialog == ExpenseItemDialog.CostCenters)
                        acquireCostCenters(null)
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
        if (state.value.restriction != null || !state.value.ready) acquireAdmission()
        else
            when (state.value.dialog) {
                ExpenseItemDialog.Categories -> acquireCategories(state.value.categories?.after)
                ExpenseItemDialog.CostCenters -> acquireCostCenters(state.value.costCenters?.after)
                else -> acquireAdmission()
            }
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
    }

    private fun onDescriptionChanged(event: ExpenseItemEvent.DescriptionChanged) {
        if (state.value.canSave)
            updateState {
                it.copy(form = it.form.copy(description = event.value.take(501)), failure = null)
            }
    }

    private fun onReceiptOpened(event: ExpenseItemEvent.ReceiptOpened) {
        if (!state.value.canSave || state.value.form.receipts.size >= 3) return
        updateState {
            it.copy(
                evidenceTarget =
                    ExpenseReceiptTarget(
                        target.workspaceId,
                        target.preparation.employee.id,
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
    }

    private fun onSaveRequested(event: ExpenseItemEvent.SaveRequested) {
        if (!state.value.canSave) return
        val form = state.value.form
        val result = prepareLine(form.toInput(target.line?.id))
        when (result) {
            is AppResult.Failed -> reportFailure(result.failure)
            is AppResult.Success -> {
                updateState { it.copy(closing = true) }
                viewModelScope.launch {
                    emitEffect(
                        ExpenseItemEffect.Saved(
                            ExpenseItemResult(
                                result.value,
                                form.category,
                                form.costCenter,
                                form.receipts,
                            )
                        )
                    )
                }
            }
        }
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
        else if (state.value.dirty) updateState { it.copy(dialog = ExpenseItemDialog.Exit) }
        else {
            updateState { it.copy(closing = true) }
            viewModelScope.launch { emitEffect(ExpenseItemEffect.Back) }
        }
    }

    private fun onExitConfirmed(event: ExpenseItemEvent.ExitConfirmed) {
        if (state.value.dialog == ExpenseItemDialog.Exit && !state.value.closing) {
            updateState { it.copy(closing = true) }
            viewModelScope.launch { emitEffect(ExpenseItemEffect.Back) }
        }
    }

    private fun onVerificationRequested(event: ExpenseItemEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(ExpenseItemEffect.VerifyAccount) }
    }

    private fun reportFailure(failure: Failure) {
        val denied = expenseFailureInvalidatesCache(failure)
        if (denied) {
            choices?.cancel()
            expiration?.cancel()
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
                )
            else it.copy(failure = failure)
        }
        if (failure.code in workspaceAccessLossCodes && !state.value.needsVerification)
            viewModelScope.launch { emitEffect(ExpenseItemEffect.RecheckWorkspace) }
    }
}
