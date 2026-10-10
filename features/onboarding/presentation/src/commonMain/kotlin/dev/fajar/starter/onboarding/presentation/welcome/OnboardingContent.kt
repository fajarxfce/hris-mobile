package dev.fajar.starter.onboarding.presentation.welcome

import dev.fajar.starter.localization.AppString

data class OnboardingContent(val title: AppString, val description: AppString) {
    companion object {
        val pages =
            listOf(
                OnboardingContent(
                    AppString.OnboardingWorkspace,
                    AppString.OnboardingWorkspaceDescription,
                ),
                OnboardingContent(
                    AppString.OnboardingActivity,
                    AppString.OnboardingActivityDescription,
                ),
                OnboardingContent(AppString.OnboardingReady, AppString.OnboardingReadyDescription),
            )
    }
}
