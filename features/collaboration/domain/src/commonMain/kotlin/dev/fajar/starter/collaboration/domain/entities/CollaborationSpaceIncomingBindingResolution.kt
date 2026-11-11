package dev.fajar.starter.collaboration.domain.entities

/** Local scheduling outcomes; none grants provider or content admission. */
enum class CollaborationSpaceIncomingBindingResolution {
    /** A logical task already names a true SPACE room; provider admission remains separate. */
    Published,
    Queued,
    BindingMissing,
    WaitingForOriginalSource,
    WaitingForPrevious,
    Obsolete,
}

data class CollaborationSpaceIncomingPhysicalResolution(
    val demand: CollaborationSpaceIncomingDemand,
    val outcome: CollaborationSpaceIncomingBindingResolution,
) {
    override fun toString() = "CollaborationSpaceIncomingPhysicalResolution(outcome=$outcome)"
}

/** New-generation Original scheduling from an admitted legacy Published alias. */
data class CollaborationSpaceIncomingAliasResolution(
    val demand: CollaborationSpaceIncomingDemand,
    val outcome: CollaborationSpaceIncomingBindingResolution,
) {
    override fun toString() = "CollaborationSpaceIncomingAliasResolution(outcome=$outcome)"
}
