package dev.fajar.starter.expenses.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.expenses.presentation.detail.*
import dev.fajar.starter.expenses.presentation.detail.pages.ExpenseClaimPage
import dev.fajar.starter.expenses.presentation.overview.*
import dev.fajar.starter.expenses.presentation.overview.pages.ExpensesPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.expenseRoutes(
    workspaceId: String,
    onClaim: (String) -> Unit,
    onVerify: () -> Unit,
    onRecheckWorkspace: () -> Unit,
    onBack: () -> Unit,
) {
    composable<ExpensesRoute> {
        val viewModel = koinViewModel<ExpensesViewModel>(parameters = { parametersOf(workspaceId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = key(state.filters) { rememberLazyListState() }
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(ExpensesEvent.Entered)
            onStopOrDispose { viewModel.onEvent(ExpensesEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                ExpensesEffect.Back -> onBack()
                ExpensesEffect.VerifyAccount -> onVerify()
                ExpensesEffect.RecheckWorkspace -> onRecheckWorkspace()
                is ExpensesEffect.OpenClaim -> onClaim(effect.id)
            }
        }
        ExpensesPage(state, list, viewModel::onEvent)
    }
    composable<ExpenseClaimRoute> { entry ->
        val target = ExpenseClaimTarget(workspaceId, entry.toRoute<ExpenseClaimRoute>().id)
        val viewModel = koinViewModel<ExpenseClaimViewModel>(parameters = { parametersOf(target) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(ExpenseClaimEvent.Entered)
            onStopOrDispose { viewModel.onEvent(ExpenseClaimEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                ExpenseClaimEffect.Back -> onBack()
                ExpenseClaimEffect.VerifyAccount -> onVerify()
                ExpenseClaimEffect.RecheckWorkspace -> onRecheckWorkspace()
            }
        }
        ExpenseClaimPage(state, list, viewModel::onEvent)
    }
}
