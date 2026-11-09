package dev.fajar.starter.collaboration.domain.entities

/** Facts from an existing owned SDK; never contains credentials or private keys. */
data class CollaborationCredentialSignal(
    val owner: CollaborationOwner,
    val deviceId: String,
    val operationId: String,
    val state: CollaborationCredentialState,
)

enum class CollaborationCredentialState {
    Rejected,
    Validated,
}
