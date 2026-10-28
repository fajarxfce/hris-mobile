package dev.fajar.starter.home.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.home.presentation.catalog.HomeService
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.home.presentation.home.pages.HomePage
import dev.fajar.starter.presentation.mvi.CollectEffects
import dev.fajar.starter.workspace.domain.entities.Workspace
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalComposeUiApi::class)
fun NavGraphBuilder.homeRoutes(
    workspace: Workspace,
    onService: (HomeService) -> Unit,
    onAllServices: () -> Unit,
    onDataSync: () -> Unit,
    onPendingActions: () -> Unit,
    onSessions: () -> Unit,
    attendance: @Composable () -> Unit,
    inbox: @Composable () -> Unit,
    employees: @Composable () -> Unit,
    inboxBadge: @Composable () -> Unit,
    onSettings: () -> Unit,
) {
    composable<HomeRoute> { entry ->
        val tab =
            HomeTab.entries.firstOrNull { it.name == entry.toRoute<HomeRoute>().tab }
                ?: HomeTab.Home
        val viewModel = koinViewModel<HomeViewModel>(parameters = { parametersOf(workspace, tab) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        BackHandler(enabled = state.tab != HomeTab.Home) {
            viewModel.onEvent(HomeEvent.BackRequested)
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                HomeEffect.OpenAllServices -> onAllServices()
                HomeEffect.OpenSettings -> onSettings()
                HomeEffect.OpenDataSync -> onDataSync()
                HomeEffect.OpenPendingActions -> onPendingActions()
                is HomeEffect.OpenService -> onService(effect.service)
                HomeEffect.OpenSessions -> onSessions()
            }
        }
        HomePage(state, viewModel::onEvent, attendance, inbox, employees, inboxBadge)
    }
}
