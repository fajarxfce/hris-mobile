package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceOwner

/** Logical live ownership; original physical owner/device/store remain on the reservation. */
internal data class SdkSpaceRuntimeIdentity(
    val owner: CollaborationSpaceOwner,
    val deviceId: String,
    val operationId: String,
) {
    override fun toString() = "SdkSpaceRuntimeIdentity([redacted])"
}
