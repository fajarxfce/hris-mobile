package dev.fajar.starter.auth.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.auth.presentation.login.LoginViewModel
import dev.fajar.starter.auth.presentation.login.pages.LoginPage
import dev.fajar.starter.auth.presentation.verification.*
import dev.fajar.starter.auth.presentation.verification.pages.VerificationPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.authRoutes() {
    composable<LoginRoute> {
        val viewModel = koinViewModel<LoginViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        LoginPage(state, viewModel::onEvent)
    }
}

fun NavGraphBuilder.verificationRoutes(sessionId: String, onVerified: () -> Unit) {
    composable<VerificationRoute> {
        val viewModel =
            koinViewModel<VerificationViewModel>(parameters = { parametersOf(sessionId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                VerificationEffect.Verified -> onVerified()
            }
        }
        VerificationPage(state, viewModel::onEvent)
    }
}
