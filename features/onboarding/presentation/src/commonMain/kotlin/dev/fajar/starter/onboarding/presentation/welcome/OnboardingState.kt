package dev.fajar.starter.onboarding.presentation.welcome

data class OnboardingState(
    val step: Int = 0,
    val saving: Boolean = false,
    val completed: Boolean = false,
    val error: String? = null,
)
