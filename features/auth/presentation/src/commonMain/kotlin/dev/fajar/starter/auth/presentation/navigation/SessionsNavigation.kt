package dev.fajar.starter.auth.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.auth.presentation.sessions.*
import dev.fajar.starter.auth.presentation.sessions.pages.SessionsPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.sessionRoutes(sessionId: String, onBack: () -> Unit) {
    composable<SessionsRoute> {
        val viewModel = koinViewModel<SessionsViewModel>(parameters = { parametersOf(sessionId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            viewModel.onEvent(SessionsEvent.RefreshRequested)
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                SessionsEffect.Back -> onBack()
            }
        }
        SessionsPage(state, viewModel::onEvent)
    }
}
