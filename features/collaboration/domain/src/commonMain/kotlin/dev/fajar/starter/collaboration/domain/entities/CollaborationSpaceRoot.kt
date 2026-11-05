package dev.fajar.starter.collaboration.domain.entities

/** Account-owned discovery facts; neither selected HR company nor a live native grant. */
data class CollaborationSpaceAccountAccess(
    val sessionId: String,
    val audience: String,
    val accountId: String,
) {
    override fun toString() = "CollaborationSpaceAccountAccess([redacted])"
}

/** A retained cache locator. Moving an existing root requires explicit migration. */
data class CollaborationSpaceRoot(val spaceId: String) {
    override fun toString() = "CollaborationSpaceRoot([redacted])"
}

fun CollaborationNativeParent.localSpaceAccountAccess() =
    CollaborationSpaceAccountAccess(sessionId, audience, accountId)

fun CollaborationSpaceRoot.localDirectoryAccess(access: CollaborationSpaceAccountAccess) =
    CollaborationSpaceLocalAccess(access.sessionId, access.audience, access.accountId, spaceId)
