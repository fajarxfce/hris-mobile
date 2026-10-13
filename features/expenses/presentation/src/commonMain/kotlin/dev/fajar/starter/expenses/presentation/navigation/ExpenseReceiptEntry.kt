package dev.fajar.starter.expenses.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fajar.starter.documents.domain.entities.DocumentEvidence
import dev.fajar.starter.expenses.presentation.receipts.*
import dev.fajar.starter.expenses.presentation.receipts.widgets.ExpenseReceiptDialog
import dev.fajar.starter.presentation.mvi.CollectEffects
import kotlin.time.Clock
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The dialog releases its own ViewModel without retaining it in the parent route's store. */
@Composable
fun ExpenseReceiptEntry(
    target: ExpenseReceiptTarget,
    onSelected: (DocumentEvidence) -> Unit,
    onDismiss: () -> Unit,
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
        koinViewModel<ExpenseReceiptViewModel>(
            viewModelStoreOwner = owner,
            parameters = { parametersOf(target, Clock.System) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val list = key(state.after) { rememberLazyListState() }
    LifecycleStartEffect(viewModel) {
        viewModel.onEvent(ExpenseReceiptEvent.Entered)
        onStopOrDispose { viewModel.onEvent(ExpenseReceiptEvent.Stopped) }
    }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is ExpenseReceiptEffect.Selected -> onSelected(effect.document)
            ExpenseReceiptEffect.Dismiss -> onDismiss()
            ExpenseReceiptEffect.VerifyAccount -> {
                onDismiss()
                onVerify()
            }
            ExpenseReceiptEffect.RecheckWorkspace -> {
                onDismiss()
                onRecheckWorkspace()
            }
        }
    }
    ExpenseReceiptDialog(state, list, viewModel::onEvent)
}
