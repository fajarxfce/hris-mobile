package dev.fajar.starter.home.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.home.presentation.home.pages.HomePage
import dev.fajar.starter.presentation.mvi.CollectEffects
import dev.fajar.starter.workspace.domain.entities.Workspace
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.homeRoutes(
    workspace: Workspace,
    onProfile: () -> Unit,
    onInbox: () -> Unit,
    onCalendar: () -> Unit,
    onSessions: () -> Unit,
    onSettings: () -> Unit,
) {
    composable<HomeRoute> { entry ->
        val tab =
            HomeTab.entries.firstOrNull { it.name == entry.toRoute<HomeRoute>().tab }
                ?: HomeTab.Home
        val viewModel = koinViewModel<HomeViewModel>(parameters = { parametersOf(workspace, tab) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                HomeEffect.OpenSettings -> onSettings()
                HomeEffect.OpenProfile -> onProfile()
                HomeEffect.OpenInbox -> onInbox()
                HomeEffect.OpenCalendar -> onCalendar()
                HomeEffect.OpenSessions -> onSessions()
            }
        }
        HomePage(state, viewModel::onEvent)
    }
}
