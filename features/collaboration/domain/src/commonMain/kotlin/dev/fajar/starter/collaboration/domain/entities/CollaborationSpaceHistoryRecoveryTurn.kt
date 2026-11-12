package dev.fajar.starter.collaboration.domain.entities

/** One admitted local metadata row. Its cursor never grants content or completion authority. */
data class CollaborationSpaceHistoryRecoveryTurn(
    val runtime: CollaborationSpaceRuntimeReady,
    val source: CollaborationSpaceIncomingSource,
    val registration: CollaborationSpaceRegistrationRecord,
    val recovery: CollaborationSpaceHistoryRecovery,
    val page: CollaborationAccountIncomingHistoryLocatorPage,
) {
    override fun toString() = "CollaborationSpaceHistoryRecoveryTurn([redacted])"
}

sealed interface CollaborationSpaceHistoryRecoverySelection {
    data object Idle : CollaborationSpaceHistoryRecoverySelection

    data class Deferred(val retry: CollaborationSpaceIncomingRetry) :
        CollaborationSpaceHistoryRecoverySelection

    data class Ready(val turn: CollaborationSpaceHistoryRecoveryTurn) :
        CollaborationSpaceHistoryRecoverySelection
}
