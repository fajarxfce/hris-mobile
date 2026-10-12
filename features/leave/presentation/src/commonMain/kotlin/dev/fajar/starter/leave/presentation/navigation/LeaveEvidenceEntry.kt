package dev.fajar.starter.leave.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fajar.starter.documents.domain.entities.DocumentEvidence
import dev.fajar.starter.leave.presentation.evidence.*
import dev.fajar.starter.leave.presentation.evidence.widgets.LeaveEvidenceDialog
import dev.fajar.starter.presentation.mvi.CollectEffects
import kotlin.time.Clock
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The dialog releases its own ViewModel without retaining it in the parent route's store. */
@Composable
fun LeaveEvidenceEntry(
    target: LeaveEvidenceTarget,
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
        koinViewModel<LeaveEvidenceViewModel>(
            viewModelStoreOwner = owner,
            parameters = { parametersOf(target, Clock.System) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val list = key(state.after) { rememberLazyListState() }
    LifecycleStartEffect(viewModel) {
        viewModel.onEvent(LeaveEvidenceEvent.Entered)
        onStopOrDispose { viewModel.onEvent(LeaveEvidenceEvent.Stopped) }
    }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is LeaveEvidenceEffect.Selected -> onSelected(effect.document)
            LeaveEvidenceEffect.Dismiss -> onDismiss()
            LeaveEvidenceEffect.VerifyAccount -> {
                onDismiss()
                onVerify()
            }
            LeaveEvidenceEffect.RecheckWorkspace -> {
                onDismiss()
                onRecheckWorkspace()
            }
        }
    }
    LeaveEvidenceDialog(state, list, viewModel::onEvent)
}
