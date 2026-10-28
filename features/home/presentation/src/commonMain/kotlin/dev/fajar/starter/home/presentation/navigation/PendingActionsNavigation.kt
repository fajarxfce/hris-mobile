package dev.fajar.starter.home.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.home.presentation.pending.*
import dev.fajar.starter.home.presentation.pending.pages.PendingActionsPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import dev.fajar.starter.workspace.domain.entities.PendingActionGroup
import dev.fajar.starter.workspace.domain.entities.Workspace
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.pendingActionsRoutes(
    workspace: Workspace,
    onOpen: (PendingActionGroup) -> Unit,
    onVerify: () -> Unit,
    onBack: () -> Unit,
) {
    composable<PendingActionsRoute> {
        val viewModel =
            koinViewModel<PendingActionsViewModel>(parameters = { parametersOf(workspace) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(PendingActionsEvent.Entered)
            onStopOrDispose { viewModel.onEvent(PendingActionsEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                PendingActionsEffect.Back -> onBack()
                PendingActionsEffect.VerifyAccount -> onVerify()
                is PendingActionsEffect.Open -> onOpen(effect.group)
            }
        }
        PendingActionsPage(state, viewModel::onEvent)
    }
}
