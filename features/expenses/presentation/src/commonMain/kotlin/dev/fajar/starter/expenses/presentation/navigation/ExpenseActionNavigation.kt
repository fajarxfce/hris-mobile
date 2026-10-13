@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter.expenses.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.expenses.presentation.action.*
import dev.fajar.starter.expenses.presentation.action.pages.ExpenseActionPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import dev.fajar.starter.presentation.navigation.enumNavType
import kotlin.reflect.typeOf
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

internal fun NavGraphBuilder.expenseActionRoute(
    workspaceId: String,
    onClaim: (ExpenseClaimRoute) -> Unit,
    onDraft: (ExpenseDraftRoute) -> Unit,
    onVerify: () -> Unit,
    onRecheckWorkspace: () -> Unit,
    onBack: () -> Unit,
) {
    composable<ExpenseActionRoute>(
        typeMap = mapOf(typeOf<ExpenseActionMode>() to enumNavType<ExpenseActionMode>())
    ) { entry ->
        val route = entry.toRoute<ExpenseActionRoute>()
        val target = ExpenseActionTarget(workspaceId, route.id, route.mode, route.employmentAfter)
        val viewModel = koinViewModel<ExpenseActionViewModel>(parameters = { parametersOf(target) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(ExpenseActionEvent.Entered)
            onStopOrDispose { viewModel.onEvent(ExpenseActionEvent.Stopped) }
        }
        BackHandler { viewModel.onEvent(ExpenseActionEvent.BackRequested) }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                ExpenseActionEffect.Back -> onBack()
                ExpenseActionEffect.VerifyAccount -> onVerify()
                ExpenseActionEffect.RecheckWorkspace -> onRecheckWorkspace()
                is ExpenseActionEffect.OpenClaim ->
                    onClaim(ExpenseClaimRoute(effect.id, effect.employmentAfter))
                is ExpenseActionEffect.EditDraft ->
                    onDraft(
                        ExpenseDraftRoute(effect.employeeId, effect.claimId, effect.employmentAfter)
                    )
            }
        }
        ExpenseActionPage(state, list, viewModel::onEvent)
    }
}
