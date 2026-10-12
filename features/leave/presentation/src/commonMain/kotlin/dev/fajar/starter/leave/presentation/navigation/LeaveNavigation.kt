package dev.fajar.starter.leave.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.leave.presentation.create.*
import dev.fajar.starter.leave.presentation.create.pages.LeaveCreatePage
import dev.fajar.starter.leave.presentation.detail.*
import dev.fajar.starter.leave.presentation.detail.pages.LeaveRequestPage
import dev.fajar.starter.leave.presentation.overview.*
import dev.fajar.starter.leave.presentation.overview.pages.LeavePage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.leaveRoutes(
    workspaceId: String,
    onRequest: (String) -> Unit,
    onCreate: (String, String) -> Unit,
    onCreatedRequest: (String) -> Unit,
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
                is LeaveEffect.OpenRequest -> onRequest(effect.id)
                is LeaveEffect.CreateRequest -> onCreate(effect.employeeId, effect.asOf)
            }
        }
        LeavePage(state, list, viewModel::onEvent)
    }
    composable<LeaveCreateRoute> { entry ->
        val route = entry.toRoute<LeaveCreateRoute>()
        val target = LeaveCreateTarget(workspaceId, route.employeeId, route.asOf)
        val viewModel = koinViewModel<LeaveCreateViewModel>(parameters = { parametersOf(target) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(LeaveCreateEvent.Entered)
            onStopOrDispose { viewModel.onEvent(LeaveCreateEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                LeaveCreateEffect.Back -> onBack()
                LeaveCreateEffect.VerifyAccount -> onVerify()
                LeaveCreateEffect.RecheckWorkspace -> onRecheckWorkspace()
                is LeaveCreateEffect.OpenRequest -> onCreatedRequest(effect.id)
            }
        }
        LeaveCreatePage(state, list, viewModel::onEvent)
        state.evidenceTarget?.let { evidenceTarget ->
            LeaveEvidenceEntry(
                evidenceTarget,
                onSelected = {
                    viewModel.onEvent(LeaveCreateEvent.EvidenceSelected(evidenceTarget, it))
                },
                onDismiss = {
                    viewModel.onEvent(LeaveCreateEvent.EvidenceDismissed(evidenceTarget))
                },
                onVerify = onVerify,
                onRecheckWorkspace = onRecheckWorkspace,
            )
        }
    }
    composable<LeaveRequestRoute> { entry ->
        val target = LeaveRequestTarget(workspaceId, entry.toRoute<LeaveRequestRoute>().id)
        val viewModel = koinViewModel<LeaveRequestViewModel>(parameters = { parametersOf(target) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = key(state.visibleSelection?.historyAfter) { rememberLazyListState() }
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(LeaveRequestEvent.Entered)
            onStopOrDispose { viewModel.onEvent(LeaveRequestEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                LeaveRequestEffect.Back -> onBack()
                LeaveRequestEffect.VerifyAccount -> onVerify()
                LeaveRequestEffect.RecheckWorkspace -> onRecheckWorkspace()
            }
        }
        LeaveRequestPage(state, list, viewModel::onEvent)
    }
}
