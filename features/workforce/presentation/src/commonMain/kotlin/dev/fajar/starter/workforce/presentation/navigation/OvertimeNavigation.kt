package dev.fajar.starter.workforce.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.presentation.mvi.CollectEffects
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeRequestSelection
import dev.fajar.starter.workforce.presentation.overtime.detail.*
import dev.fajar.starter.workforce.presentation.overtime.detail.pages.OvertimeRequestPage
import dev.fajar.starter.workforce.presentation.overtime.overview.*
import dev.fajar.starter.workforce.presentation.overtime.overview.pages.OvertimePage
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.overtimeRoutes(
    workspaceId: String,
    onRequest: (OvertimeRequestSelection) -> Unit,
    onVerify: () -> Unit,
    onRecheckWorkspace: () -> Unit,
    onBack: () -> Unit,
) {
    composable<OvertimeRoute> {
        val viewModel = koinViewModel<OvertimeViewModel>(parameters = { parametersOf(workspaceId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = key(state.selection) { rememberLazyListState() }
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(OvertimeEvent.Entered)
            onStopOrDispose { viewModel.onEvent(OvertimeEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                OvertimeEffect.Back -> onBack()
                OvertimeEffect.VerifyAccount -> onVerify()
                OvertimeEffect.RecheckWorkspace -> onRecheckWorkspace()
                is OvertimeEffect.OpenRequest -> onRequest(effect.selection)
            }
        }
        OvertimePage(state, list, viewModel::onEvent)
    }
    composable<OvertimeRequestRoute> { entry ->
        val route = entry.toRoute<OvertimeRequestRoute>()
        val target =
            OvertimeRequestTarget(
                workspaceId,
                OvertimeRequestSelection(route.id, route.employeeId, route.employmentAfter),
            )
        val viewModel =
            koinViewModel<OvertimeRequestViewModel>(parameters = { parametersOf(target) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = key(state.selection.historyAfter) { rememberLazyListState() }
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(OvertimeRequestEvent.Entered)
            onStopOrDispose { viewModel.onEvent(OvertimeRequestEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                OvertimeRequestEffect.Back -> onBack()
                OvertimeRequestEffect.VerifyAccount -> onVerify()
                OvertimeRequestEffect.RecheckWorkspace -> onRecheckWorkspace()
            }
        }
        OvertimeRequestPage(state, list, viewModel::onEvent)
    }
}
