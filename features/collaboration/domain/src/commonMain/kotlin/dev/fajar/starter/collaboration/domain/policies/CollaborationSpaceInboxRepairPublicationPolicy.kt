package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Terminal publication requests new work without advancing or resetting a captured pass. */
fun validCollaborationSpaceInboxRepairPublication(
    value: CollaborationSpaceInboxRepairPublication
): Boolean {
    if (value.expected?.latestDemand == value.replacement.latestDemand) return false
    return collaborationSpaceInboxRepairProgress(
        value.expected,
        CollaborationSpaceInboxRepairProgressChange.Request(value.replacement.latestDemand),
        value.replacement.generation,
    ) == AppResult.Success(value.replacement)
}
