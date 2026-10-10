package dev.fajar.starter.onboarding.presentation.welcome

sealed interface OnboardingEffect {
    data object Completed : OnboardingEffect
}
