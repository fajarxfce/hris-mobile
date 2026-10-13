package dev.fajar.starter.expenses.presentation.detail

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.policies.expenseFailureInvalidatesCache
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.expenses.presentation.expenseVerificationCodes
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ExpenseClaimViewModel(
    @InjectedParam private val target: ExpenseClaimTarget,
    private val observe: ObserveExpenseClaim,
    private val refresh: RefreshExpenseClaim,
) : MviViewModel<ExpenseClaimState, ExpenseClaimEvent, ExpenseClaimEffect>(ExpenseClaimState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null

    init {
        on<ExpenseClaimEvent.EditRequested>(::onEditRequested)
        on<ExpenseClaimEvent.Entered>(::onEntered)
        on<ExpenseClaimEvent.Stopped>(::onStopped)
        on<ExpenseClaimEvent.RefreshRequested>(::onRefreshRequested)
        on<ExpenseClaimEvent.HistoryRequested>(::onHistoryRequested)
        on<ExpenseClaimEvent.BackRequested>(::onBackRequested)
        on<ExpenseClaimEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: ExpenseClaimEvent.Entered) {
        if (observation?.isActive == true) return
        updateState { it.copy(loading = true) }
        observeContent()
        onEvent(ExpenseClaimEvent.RefreshRequested)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeContent() {
        observation?.cancel()
        observation =
            viewModelScope.launch {
                state
                    .map { it.historyAfter to it.readRestricted }
                    .distinctUntilChanged()
                    .flatMapLatest { (after, restricted) ->
                        if (restricted) emptyFlow()
                        else observe(target.workspaceId, target.id).map { after to it }
                    }
                    .collect { (after, result) ->
                        ensureActive()
                        if (after == state.value.historyAfter && !state.value.readRestricted)
                            updateState { it.withCachedClaim(result) }
                    }
            }
    }

    private fun onStopped(event: ExpenseClaimEvent.Stopped) {
        observation?.cancel()
        observation = null
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                historyAfter =
                    if (it.content != null) it.content.value.historyAfter else it.historyAfter,
                content = null,
                loading = false,
                refreshing = false,
            )
        }
    }

    private fun onRefreshRequested(event: ExpenseClaimEvent.RefreshRequested) {
        if (observation?.isActive != true || acquisition?.isActive == true) return
        val after = state.value.historyAfter
        updateState {
            it.copy(
                refreshing = true,
                cacheFailure =
                    it.refreshFailure?.takeIf(::expenseFailureInvalidatesCache) ?: it.cacheFailure,
                refreshFailure = it.refreshFailure?.takeIf(::expenseFailureInvalidatesCache),
            )
        }
        acquisition =
            viewModelScope.launch {
                val result = refresh(target.workspaceId, target.id, after)
                ensureActive()
                if (result is AppResult.Success) observation?.cancel()
                updateState { it.withClaimRefresh(result) }
                if (result is AppResult.Success) observeContent()
                if (
                    result is AppResult.Failed &&
                        result.failure.code in workspaceAccessLossCodes &&
                        result.failure.code !in expenseVerificationCodes
                )
                    emitEffect(ExpenseClaimEffect.RecheckWorkspace)
            }
    }

    private fun onHistoryRequested(event: ExpenseClaimEvent.HistoryRequested) {
        val content = state.value.content?.value ?: return
        if (
            state.value.readRestricted ||
                event.after == content.historyAfter ||
                (event.after == state.value.historyAfter && acquisition?.isActive == true) ||
                (event.after != null && event.after.toString() != content.history.nextCursor)
        )
            return
        acquisition?.cancel()
        acquisition = null
        updateState {
            it.copy(
                historyAfter = event.after,
                loading = false,
                refreshing = false,
                cacheFailure = null,
                refreshFailure = null,
            )
        }
        onEvent(ExpenseClaimEvent.RefreshRequested)
    }

    private fun onEditRequested(event: ExpenseClaimEvent.EditRequested) {
        if (!state.value.canEdit || state.value.refreshing) return
        val claim = state.value.content?.value?.claim ?: return
        viewModelScope.launch {
            emitEffect(
                ExpenseClaimEffect.EditDraft(claim.employeeId, claim.id, target.employmentAfter)
            )
        }
    }

    private fun onBackRequested(event: ExpenseClaimEvent.BackRequested) {
        viewModelScope.launch { emitEffect(ExpenseClaimEffect.Back) }
    }

    private fun onVerificationRequested(event: ExpenseClaimEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(ExpenseClaimEffect.VerifyAccount) }
    }
}
