package dev.fajar.starter.workspace.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.presentation.mvi.CollectEffects
import dev.fajar.starter.workspace.presentation.selection.*
import dev.fajar.starter.workspace.presentation.selection.pages.WorkspacePage
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.workspaceRoutes(sessionId: String, onVerificationRequired: () -> Unit) {
    composable<WorkspaceRoute> {
        val viewModel = koinViewModel<WorkspaceViewModel>(parameters = { parametersOf(sessionId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                WorkspaceEffect.VerificationRequired -> onVerificationRequired()
            }
        }
        WorkspacePage(state, viewModel::onEvent)
    }
}
