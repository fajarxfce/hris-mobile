package dev.fajar.starter.dashboard.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.dashboard.presentation.home.DashboardViewModel
import dev.fajar.starter.dashboard.presentation.home.pages.DashboardPage
import org.koin.compose.viewmodel.koinViewModel

fun NavGraphBuilder.dashboardRoutes() {
    composable<DashboardRoute> {
        val viewModel = koinViewModel<DashboardViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        DashboardPage(state, viewModel::onEvent)
    }
}
