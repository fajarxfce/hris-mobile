package dev.fajar.starter.collaboration.domain.entities

/** Exact encrypted queue revision; account/space ownership remains an explicit repository input. */
data class CollaborationSpaceIncomingDemandSnapshot(
    val generation: String,
    val demand: CollaborationSpaceIncomingDemand,
) {
    override fun toString() = "CollaborationSpaceIncomingDemandSnapshot([redacted])"
}

/** Independent scheduling generation. It never acknowledges outgoing recovery or grants access. */
data class CollaborationSpaceIncomingWake(val generation: String) {
    companion object {
        const val WORK_KEY = "hris.collaboration-space-incoming"
    }

    override fun toString() = "CollaborationSpaceIncomingWake([redacted])"
}
