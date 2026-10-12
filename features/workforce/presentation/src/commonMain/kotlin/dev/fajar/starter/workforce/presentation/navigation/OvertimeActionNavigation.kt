package dev.fajar.starter.workforce.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.presentation.mvi.CollectEffects
import dev.fajar.starter.presentation.navigation.enumNavType
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeRequestSelection
import dev.fajar.starter.workforce.presentation.overtime.action.*
import dev.fajar.starter.workforce.presentation.overtime.action.pages.OvertimeActionPage
import kotlin.reflect.typeOf
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

internal fun NavGraphBuilder.overtimeActionRoute(
    workspaceId: String,
    onVerify: () -> Unit,
    onRecheckWorkspace: () -> Unit,
    onBack: () -> Unit,
) {
    composable<OvertimeActionRoute>(
        typeMap = mapOf(typeOf<OvertimeActionKind>() to enumNavType<OvertimeActionKind>())
    ) { entry ->
        val route = entry.toRoute<OvertimeActionRoute>()
        val target =
            OvertimeActionTarget(
                workspaceId,
                OvertimeRequestSelection(route.id, route.employeeId, route.employmentAfter),
                route.kind,
            )
        val viewModel =
            koinViewModel<OvertimeActionViewModel>(parameters = { parametersOf(target) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(OvertimeActionEvent.Entered)
            onStopOrDispose { viewModel.onEvent(OvertimeActionEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                OvertimeActionEffect.Back -> onBack()
                OvertimeActionEffect.VerifyAccount -> onVerify()
                OvertimeActionEffect.RecheckWorkspace -> onRecheckWorkspace()
            }
        }
        OvertimeActionPage(state, list, viewModel::onEvent)
    }
}
