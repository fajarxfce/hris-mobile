package dev.fajar.starter.onboarding.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.onboarding.presentation.welcome.OnboardingEffect
import dev.fajar.starter.onboarding.presentation.welcome.OnboardingViewModel
import dev.fajar.starter.onboarding.presentation.welcome.pages.OnboardingPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel

fun NavGraphBuilder.onboardingRoutes(onCompleted: () -> Unit) {
    composable<OnboardingRoute> {
        val viewModel = koinViewModel<OnboardingViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                OnboardingEffect.Completed -> onCompleted()
            }
        }
        OnboardingPage(state, viewModel::onEvent)
    }
}
