package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationSpaceIncomingDemandRepository {
    suspend fun current(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationSpaceIncomingDemandSnapshot?>

    /**
     * Atomic exact-snapshot replacement. A supplied incoming wake commits with the queue; capacity,
     * ownership or concurrent changes return null without either mutation.
     */
    suspend fun replace(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceIncomingDemandSnapshot?,
        demand: CollaborationSpaceIncomingDemand,
        wake: CollaborationSpaceIncomingWake? = null,
    ): AppResult<CollaborationSpaceIncomingDemandSnapshot?>
}
