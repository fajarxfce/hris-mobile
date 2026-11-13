package dev.fajar.starter.collaboration.domain.entities

/**
 * Exact immutable original command intent retained until authoritative command progress is
 * published.
 */
data class CollaborationSpaceInboxRepairReference(
    val intent: CollaborationSpaceMessageIntent,
    /**
     * Index revision when this reference was first retained; unrelated to visible chat ordering.
     */
    val insertedAtRevision: Long,
) {
    override fun toString() = "CollaborationSpaceInboxRepairReference([redacted])"
}

sealed interface CollaborationSpaceInboxRepairChange {
    data class Retain(val intent: CollaborationSpaceMessageIntent) :
        CollaborationSpaceInboxRepairChange {
        override fun toString() = "Retain([redacted])"
    }

    data class Complete(val reference: CollaborationSpaceInboxRepairReference) :
        CollaborationSpaceInboxRepairChange {
        override fun toString() = "Complete([redacted])"
    }
}

sealed interface CollaborationSpaceInboxRepairPage {
    data object Restart : CollaborationSpaceInboxRepairPage

    data class Items(
        val version: CollaborationSpaceInboxVersion?,
        val references: List<CollaborationSpaceInboxRepairReference>,
        val next: String?,
    ) : CollaborationSpaceInboxRepairPage {
        override fun toString() = "CollaborationSpaceInboxRepairPage([redacted])"
    }
}
