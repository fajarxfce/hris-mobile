package dev.fajar.starter.collaboration.domain.entities

/** Both immutable attempts remain owned facts; preparing a replacement does not resolve either. */
data class CollaborationSpaceBootstrapReplacement(
    val supersedes: CollaborationSpaceBootstrap,
    val replacement: CollaborationSpaceBootstrap,
) {
    override fun toString() = "CollaborationSpaceBootstrapReplacement([redacted])"
}

/** Typed supersession proof, not an acknowledgment that the original historically applied. */
data class CollaborationSpaceBootstrapReplacementReceipt(
    val superseded: CollaborationSpaceBootstrap,
    val replacementOperationId: String,
    val gateway: CollaborationSpaceGateway,
) {
    override fun toString() = "CollaborationSpaceBootstrapReplacementReceipt([redacted])"
}
