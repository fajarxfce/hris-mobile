package dev.fajar.starter.onboarding.presentation.welcome

data class OnboardingContent(val title: String, val description: String) {
    companion object {
        val pages =
            listOf(
                OnboardingContent(
                    "Your workspace",
                    "Access your projects and account in one place.",
                ),
                OnboardingContent("Stay up to date", "Review recent activity and follow progress."),
                OnboardingContent("Ready to begin", "Sign in to open your workspace."),
            )
    }
}
