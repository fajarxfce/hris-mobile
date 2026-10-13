package dev.fajar.starter.app.bootstrap

sealed interface AppStage {
    data object Loading : AppStage

    data object Onboarding : AppStage

    data object SignedOut : AppStage

    data class SignedIn(
        val sessionId: String,
        val workspace: dev.fajar.starter.workspace.domain.entities.Workspace? = null,
        val expiredWorkspaceId: String? = null,
    ) : AppStage {
        val selectedWorkspaceId: String?
            get() = workspace?.id ?: expiredWorkspaceId
    }

    data class Failed(val failure: dev.fajar.starter.common.result.Failure) : AppStage
}
