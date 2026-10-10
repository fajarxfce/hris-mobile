package dev.fajar.starter.dashboard.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.dashboard.presentation.home.DashboardTab
import dev.fajar.starter.dashboard.presentation.home.DashboardViewModel
import dev.fajar.starter.dashboard.presentation.home.pages.DashboardPage
import org.koin.compose.viewmodel.koinViewModel

fun NavGraphBuilder.dashboardRoutes(
    onNotifications: () -> Unit,
    onSettings: () -> Unit,
    onFiles: () -> Unit,
) {
    composable<DashboardRoute> { entry ->
        val route = entry.toRoute<DashboardRoute>()
        val viewModel =
            koinViewModel<DashboardViewModel>(
                parameters = {
                    org.koin.core.parameter.parametersOf(
                        DashboardTab.entries.firstOrNull { it.name == route.tab }
                            ?: DashboardTab.Overview
                    )
                }
            )
        val state by viewModel.state.collectAsStateWithLifecycle()
        dev.fajar.starter.presentation.mvi.CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                dev.fajar.starter.dashboard.presentation.home.DashboardEffect.OpenFiles -> onFiles()
                dev.fajar.starter.dashboard.presentation.home.DashboardEffect.OpenSettings ->
                    onSettings()
                dev.fajar.starter.dashboard.presentation.home.DashboardEffect.OpenNotifications ->
                    onNotifications()
            }
        }
        DashboardPage(state, viewModel::onEvent)
    }
}
