package dev.fajar.starter.workforce.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fajar.starter.presentation.mvi.CollectEffects
import dev.fajar.starter.workforce.presentation.attendance.*
import dev.fajar.starter.workforce.presentation.attendance.widgets.*
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Home hosts this feature entry; its ViewModel and I/O stop when the tab or route leaves. */
@Composable
fun AttendanceHomeContent(workspaceId: String, onEffect: (AttendanceEffect) -> Unit) {
    val viewModel =
        koinViewModel<AttendanceViewModel>(
            key = "home-attendance",
            parameters = { parametersOf(workspaceId) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.onEvent(AttendanceEvent.Entered)
        onStopOrDispose { viewModel.onEvent(AttendanceEvent.Stopped) }
    }
    CollectEffects(viewModel.effects, onEffect)
    AttendanceHomeCard(state, viewModel::onEvent)
}
