package dev.fajar.starter.collaboration.domain.entities

/** A fresh repair Request prepared by the domain owner for atomic terminal publication. */
data class CollaborationSpaceInboxRepairPublication(
    val expected: CollaborationSpaceInboxRepairProgress?,
    val replacement: CollaborationSpaceInboxRepairProgress,
) {
    override fun toString() = "CollaborationSpaceInboxRepairPublication([redacted])"
}
