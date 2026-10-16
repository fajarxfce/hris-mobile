package dev.fajar.starter.home.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.home.presentation.datasync.*
import dev.fajar.starter.home.presentation.datasync.pages.DataSyncPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import dev.fajar.starter.workspace.domain.entities.Workspace
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.dataSyncRoutes(workspace: Workspace, onVerify: () -> Unit, onBack: () -> Unit) {
    composable<DataSyncRoute> {
        val viewModel = koinViewModel<DataSyncViewModel>(parameters = { parametersOf(workspace) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(DataSyncEvent.Entered)
            onStopOrDispose { viewModel.onEvent(DataSyncEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                DataSyncEffect.Back -> onBack()
                DataSyncEffect.VerifyAccount -> onVerify()
            }
        }
        DataSyncPage(state, viewModel::onEvent)
    }
}
