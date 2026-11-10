package dev.fajar.starter.collaboration.domain.entities

/** Local startup signal only; the incoming task must still acquire its current native grant. */
data class CollaborationSpaceIncomingStartup(
    val owner: CollaborationSpaceOwner,
    val registrationId: String,
    val operationId: String,
    val deviceId: String,
) {
    override fun toString() = "CollaborationSpaceIncomingStartup([redacted])"
}
