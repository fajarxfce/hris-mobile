package dev.fajar.starter.leave.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.leave.presentation.overview.*
import dev.fajar.starter.leave.presentation.overview.pages.LeavePage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.leaveRoutes(
    workspaceId: String,
    onVerify: () -> Unit,
    onRecheckWorkspace: () -> Unit,
    onBack: () -> Unit,
) {
    composable<LeaveRoute> {
        val viewModel = koinViewModel<LeaveViewModel>(parameters = { parametersOf(workspaceId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = key(state.selection) { rememberLazyListState() }
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(LeaveEvent.Entered)
            onStopOrDispose { viewModel.onEvent(LeaveEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                LeaveEffect.Back -> onBack()
                LeaveEffect.VerifyAccount -> onVerify()
                LeaveEffect.RecheckWorkspace -> onRecheckWorkspace()
            }
        }
        LeavePage(state, list, viewModel::onEvent)
    }
}
