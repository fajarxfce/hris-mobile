package dev.fajar.starter.auth.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.auth.presentation.login.LoginViewModel
import dev.fajar.starter.auth.presentation.login.pages.LoginPage
import org.koin.compose.viewmodel.koinViewModel

fun NavGraphBuilder.authRoutes() {
    composable<LoginRoute> {
        val viewModel = koinViewModel<LoginViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        LoginPage(state, viewModel::onEvent)
    }
}
