package dev.fajar.starter.payroll.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.payroll.presentation.detail.*
import dev.fajar.starter.payroll.presentation.detail.pages.PayslipPage
import dev.fajar.starter.payroll.presentation.overview.*
import dev.fajar.starter.payroll.presentation.overview.pages.PayslipsPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.payrollRoutes(
    workspaceId: String,
    onPayslip: (String) -> Unit,
    onVerify: () -> Unit,
    onRecheckWorkspace: () -> Unit,
    onBack: () -> Unit,
) {
    composable<PayslipsRoute> {
        val viewModel = koinViewModel<PayslipsViewModel>(parameters = { parametersOf(workspaceId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = key(state.filters) { rememberLazyListState() }
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(PayslipsEvent.Entered)
            onStopOrDispose { viewModel.onEvent(PayslipsEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                PayslipsEffect.Back -> onBack()
                PayslipsEffect.VerifyAccount -> onVerify()
                PayslipsEffect.RecheckWorkspace -> onRecheckWorkspace()
                is PayslipsEffect.OpenPayslip -> onPayslip(effect.id)
            }
        }
        PayslipsPage(state, list, viewModel::onEvent)
    }
    composable<PayslipRoute> { entry ->
        val route = entry.toRoute<PayslipRoute>()
        val target = PayslipTarget(workspaceId, route.id)
        val viewModel = koinViewModel<PayslipViewModel>(parameters = { parametersOf(target) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        // A system save dialog can stop the Activity. Export belongs to this route's disposal.
        DisposableEffect(viewModel) {
            viewModel.onEvent(PayslipEvent.Entered)
            onDispose { viewModel.onEvent(PayslipEvent.Closed) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                PayslipEffect.Back -> onBack()
                PayslipEffect.VerifyAccount -> onVerify()
                PayslipEffect.RecheckWorkspace -> onRecheckWorkspace()
            }
        }
        PayslipPage(state, list, viewModel::onEvent)
    }
}
