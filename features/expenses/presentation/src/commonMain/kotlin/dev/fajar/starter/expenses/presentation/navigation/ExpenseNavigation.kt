package dev.fajar.starter.expenses.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.expenses.presentation.detail.*
import dev.fajar.starter.expenses.presentation.detail.pages.ExpenseClaimPage
import dev.fajar.starter.expenses.presentation.draft.ExpenseDraftTarget
import dev.fajar.starter.expenses.presentation.overview.*
import dev.fajar.starter.expenses.presentation.overview.pages.ExpensesPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.expenseRoutes(
    workspaceId: String,
    onClaim: (ExpenseClaimRoute) -> Unit,
    onDraft: (ExpenseDraftRoute) -> Unit,
    onSavedClaim: (ExpenseClaimRoute) -> Unit,
    onAction: (ExpenseActionRoute) -> Unit,
    onActionClaim: (ExpenseClaimRoute) -> Unit,
    onActionDraft: (ExpenseDraftRoute) -> Unit,
    onVerify: () -> Unit,
    onRecheckWorkspace: () -> Unit,
    onBack: () -> Unit,
) {
    expenseActionRoute(
        workspaceId,
        onActionClaim,
        onActionDraft,
        onVerify,
        onRecheckWorkspace,
        onBack,
    )
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
                is ExpensesEffect.OpenClaim ->
                    onClaim(ExpenseClaimRoute(effect.id, effect.employmentAfter))
                is ExpensesEffect.OpenDraft ->
                    onDraft(
                        ExpenseDraftRoute(
                            effect.employeeId,
                            employmentAfter = effect.employmentAfter,
                        )
                    )
            }
        }
        ExpensesPage(state, list, viewModel::onEvent)
    }
    composable<ExpenseDraftRoute> { entry ->
        val route = entry.toRoute<ExpenseDraftRoute>()
        ExpenseDraftEntry(
            ExpenseDraftTarget(workspaceId, route.employeeId, route.claimId, route.employmentAfter),
            onSavedClaim,
            onVerify,
            onRecheckWorkspace,
            onBack,
        )
    }
    composable<ExpenseClaimRoute> { entry ->
        val route = entry.toRoute<ExpenseClaimRoute>()
        val target = ExpenseClaimTarget(workspaceId, route.id, route.employmentAfter)
        val viewModel = koinViewModel<ExpenseClaimViewModel>(parameters = { parametersOf(target) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        DisposableEffect(viewModel) { onDispose { viewModel.onEvent(ExpenseClaimEvent.Closed) } }
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(ExpenseClaimEvent.Entered)
            onStopOrDispose { viewModel.onEvent(ExpenseClaimEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                is ExpenseClaimEffect.OpenAction ->
                    onAction(ExpenseActionRoute(effect.id, effect.mode, effect.employmentAfter))
                is ExpenseClaimEffect.EditDraft ->
                    onDraft(
                        ExpenseDraftRoute(effect.employeeId, effect.claimId, effect.employmentAfter)
                    )
                ExpenseClaimEffect.Back -> onBack()
                ExpenseClaimEffect.VerifyAccount -> onVerify()
                ExpenseClaimEffect.RecheckWorkspace -> onRecheckWorkspace()
            }
        }
        ExpenseClaimPage(state, list, viewModel::onEvent)
    }
}
