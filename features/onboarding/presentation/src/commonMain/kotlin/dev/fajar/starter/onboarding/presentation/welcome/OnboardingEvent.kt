package dev.fajar.starter.onboarding.presentation.welcome

sealed interface OnboardingEvent {
    data object NextRequested : OnboardingEvent

    data object BackRequested : OnboardingEvent

    data object FinishRequested : OnboardingEvent
}
