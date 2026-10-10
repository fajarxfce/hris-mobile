package dev.fajar.starter.app.bootstrap

sealed interface AppStage {
    data object Loading : AppStage

    data object Onboarding : AppStage

    data object SignedOut : AppStage

    data object SignedIn : AppStage

    data class Failed(val message: String) : AppStage
}
