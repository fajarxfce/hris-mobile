package dev.fajar.starter.collaboration.domain.entities

/** Local original recovery coordinates; adoption and live synchronization require admission. */
data class CollaborationSpaceRecoveryDevice(
    val owner: CollaborationSpaceOwner,
    val registrationId: String,
    val operationId: String,
) {
    override fun toString() = "CollaborationSpaceRecoveryDevice([redacted])"
}
