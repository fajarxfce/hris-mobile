package dev.fajar.starter.collaboration.domain.entities

/** Foreground selection and finite background admission have distinct owners. */
sealed interface CollaborationOperationScope {
    data class Selected(val workspaceId: String) : CollaborationOperationScope

    data class Background(val admission: CollaborationBackgroundAdmission) :
        CollaborationOperationScope {
        override fun toString() = "Background([redacted])"
    }
}
