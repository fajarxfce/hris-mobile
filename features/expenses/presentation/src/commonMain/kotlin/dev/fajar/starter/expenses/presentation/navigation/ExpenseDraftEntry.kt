@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter.expenses.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fajar.starter.expenses.presentation.draft.*
import dev.fajar.starter.expenses.presentation.draft.pages.ExpenseDraftPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun ExpenseDraftEntry(
    target: ExpenseDraftTarget,
    onSavedClaim: (ExpenseClaimRoute) -> Unit,
    onVerify: () -> Unit,
    onRecheckWorkspace: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = koinViewModel<ExpenseDraftViewModel>(parameters = { parametersOf(target) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LifecycleStartEffect(viewModel) {
        viewModel.onEvent(ExpenseDraftEvent.Entered)
        onStopOrDispose { viewModel.onEvent(ExpenseDraftEvent.Stopped) }
    }
    BackHandler(enabled = state.itemTarget == null) {
        viewModel.onEvent(ExpenseDraftEvent.BackRequested)
    }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            ExpenseDraftEffect.Back -> onBack()
            ExpenseDraftEffect.VerifyAccount -> onVerify()
            ExpenseDraftEffect.RecheckWorkspace -> onRecheckWorkspace()
            is ExpenseDraftEffect.OpenClaim ->
                onSavedClaim(ExpenseClaimRoute(effect.id, effect.employmentAfter))
        }
    }
    val item = state.itemTarget
    if (item == null) ExpenseDraftPage(state, list, viewModel::onEvent)
    else
        ExpenseItemEntry(
            item,
            { viewModel.onEvent(ExpenseDraftEvent.ItemSaved(item, it)) },
            { viewModel.onEvent(ExpenseDraftEvent.ItemDismissed(item)) },
            onVerify,
            onRecheckWorkspace,
        )
}
