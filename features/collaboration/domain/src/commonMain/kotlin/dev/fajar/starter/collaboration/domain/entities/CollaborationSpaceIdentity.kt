package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Already selected provider user; neither a device grant nor proof that original keys exist. */
data class CollaborationSpaceIdentityObservation(
    val owner: CollaborationSpaceOwner,
    val identity: CollaborationSpaceIdentity?,
) {
    override fun toString() = "CollaborationSpaceIdentityObservation([redacted])"
}

data class CollaborationSpaceIdentity(
    val spaceId: String,
    val accountId: String,
    val serverName: String,
    val matrixUserId: String,
    val origin: CollaborationSpaceIdentityOrigin,
    val legacyCompanyId: String?,
    val version: Long,
    val createdAt: Instant,
) {
    override fun toString() = "CollaborationSpaceIdentity(origin=$origin, version=$version)"
}

enum class CollaborationSpaceIdentityOrigin {
    LEGACY,
    SPACE,
}
