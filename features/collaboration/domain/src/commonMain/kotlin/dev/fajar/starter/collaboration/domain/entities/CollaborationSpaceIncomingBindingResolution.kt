package dev.fajar.starter.collaboration.domain.entities

/** Local scheduling outcomes; none grants provider or content admission. */
enum class CollaborationSpaceIncomingBindingResolution {
    Queued,
    BindingMissing,
    WaitingForPrevious,
    Obsolete,
}

data class CollaborationSpaceIncomingPhysicalResolution(
    val demand: CollaborationSpaceIncomingDemand,
    val outcome: CollaborationSpaceIncomingBindingResolution,
) {
    override fun toString() = "CollaborationSpaceIncomingPhysicalResolution(outcome=$outcome)"
}
