package dev.fajar.starter.collaboration.domain.entities

/** A constant account/root-owned metadata pass; retained coordinates grant no native authority. */
data class CollaborationSpaceHistoryRecovery(
    val generation: String,
    val revision: Long = 0,
    val afterPartition: String? = null,
    val moreHistoryPages: Boolean = false,
    val unresolved: Boolean = false,
    val complete: Boolean = false,
    val requestedGeneration: String? = null,
    val retry: CollaborationSpaceIncomingRetry? = null,
    /**
     * Null selects account-wide history; a nonempty canonical list selects known physical rooms.
     */
    val roomIds: List<String>? = null,
    /** The requested pass owns its own predicate; adding work never changes the active cursor. */
    val requestedRoomIds: List<String>? = null,
) {
    override fun toString() = "CollaborationSpaceHistoryRecovery([redacted])"
}
