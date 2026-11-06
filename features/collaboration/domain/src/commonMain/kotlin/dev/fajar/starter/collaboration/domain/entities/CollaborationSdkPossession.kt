package dev.fajar.starter.collaboration.domain.entities

/**
 * Identity derived from the existing private account under its directory lease, not live access.
 */
data class CollaborationSdkPossession(
    val inventory: CollaborationSdkInventory,
    val identity: CollaborationLocalDeviceIdentity,
) {
    override fun toString() = "CollaborationSdkPossession([redacted])"
}
