@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter.expenses.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fajar.starter.expenses.presentation.item.*
import dev.fajar.starter.expenses.presentation.item.pages.ExpenseItemPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import kotlin.time.Clock
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun ExpenseItemEntry(
    target: ExpenseItemTarget,
    onSaved: (ExpenseItemResult) -> Unit,
    onBack: () -> Unit,
    onVerify: () -> Unit,
    onRecheckWorkspace: () -> Unit,
) {
    val owner =
        remember(target) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val viewModel =
        koinViewModel<ExpenseItemViewModel>(
            viewModelStoreOwner = owner,
            parameters = { parametersOf(target, Clock.System) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LifecycleStartEffect(viewModel) {
        viewModel.onEvent(ExpenseItemEvent.Entered)
        onStopOrDispose { viewModel.onEvent(ExpenseItemEvent.Stopped) }
    }
    BackHandler { viewModel.onEvent(ExpenseItemEvent.BackRequested) }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            ExpenseItemEffect.Back -> onBack()
            ExpenseItemEffect.VerifyAccount -> onVerify()
            ExpenseItemEffect.RecheckWorkspace -> onRecheckWorkspace()
            is ExpenseItemEffect.Saved -> onSaved(effect.value)
        }
    }
    ExpenseItemPage(state, list, viewModel::onEvent)
    state.evidenceTarget?.let { receipt ->
        ExpenseReceiptEntry(
            receipt,
            { viewModel.onEvent(ExpenseItemEvent.ReceiptSelected(receipt, it)) },
            { viewModel.onEvent(ExpenseItemEvent.ReceiptDismissed(receipt)) },
            onVerify,
            onRecheckWorkspace,
        )
    }
}
