package dev.fajar.starter.workforce.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.presentation.mvi.CollectEffects
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeRequestSelection
import dev.fajar.starter.workforce.presentation.overtime.plan.*
import dev.fajar.starter.workforce.presentation.overtime.plan.pages.OvertimePlanPage
import kotlin.time.Clock
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

internal fun NavGraphBuilder.overtimePlanRoute(
    workspaceId: String,
    onRequest: (OvertimeRequestSelection) -> Unit,
    onVerify: () -> Unit,
    onRecheckWorkspace: () -> Unit,
    onBack: () -> Unit,
) {
    composable<OvertimePlanRoute> { entry ->
        val route = entry.toRoute<OvertimePlanRoute>()
        val target =
            OvertimePlanTarget(workspaceId, route.employeeId, route.workDate, route.employmentAfter)
        val viewModel =
            koinViewModel<OvertimePlanViewModel>(
                parameters = { parametersOf(target, Clock.System) }
            )
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(OvertimePlanEvent.Entered)
            onStopOrDispose { viewModel.onEvent(OvertimePlanEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                OvertimePlanEffect.Back -> onBack()
                OvertimePlanEffect.VerifyAccount -> onVerify()
                OvertimePlanEffect.RecheckWorkspace -> onRecheckWorkspace()
                is OvertimePlanEffect.OpenRequest -> onRequest(effect.selection)
            }
        }
        OvertimePlanPage(state, list, viewModel::onEvent)
    }
}
