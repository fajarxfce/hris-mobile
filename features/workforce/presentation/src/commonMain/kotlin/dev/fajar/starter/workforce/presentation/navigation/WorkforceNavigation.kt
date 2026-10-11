package dev.fajar.starter.workforce.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.presentation.mvi.CollectEffects
import dev.fajar.starter.workforce.presentation.attendance.*
import dev.fajar.starter.workforce.presentation.attendance.pages.AttendancePage
import dev.fajar.starter.workforce.presentation.calendar.*
import dev.fajar.starter.workforce.presentation.calendar.pages.WorkCalendarPage
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.workforceRoutes(
    workspaceId: String,
    onVerify: () -> Unit,
    onRecheckWorkspace: () -> Unit,
    onBack: () -> Unit,
) {
    composable<AttendanceRoute> {
        val viewModel =
            koinViewModel<AttendanceViewModel>(parameters = { parametersOf(workspaceId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(AttendanceEvent.Entered)
            onStopOrDispose { viewModel.onEvent(AttendanceEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                AttendanceEffect.Back -> onBack()
                AttendanceEffect.VerifyAccount -> onVerify()
                AttendanceEffect.RecheckWorkspace -> onRecheckWorkspace()
            }
        }
        AttendancePage(state, list, viewModel::onEvent)
    }
    composable<WorkCalendarRoute> {
        val viewModel =
            koinViewModel<WorkCalendarViewModel>(parameters = { parametersOf(workspaceId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        LifecycleEventEffect(Lifecycle.Event.ON_START) {
            viewModel.onEvent(WorkCalendarEvent.Entered)
        }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
            viewModel.onEvent(WorkCalendarEvent.Stopped)
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                WorkCalendarEffect.Back -> onBack()
                WorkCalendarEffect.VerifyAccount -> onVerify()
                WorkCalendarEffect.RecheckWorkspace -> onRecheckWorkspace()
            }
        }
        WorkCalendarPage(state, list, viewModel::onEvent)
    }
}
