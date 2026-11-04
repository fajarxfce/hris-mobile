package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Finite native metadata reads. Neither operation selects, enrolls or opens a provider device. */
interface CollaborationSpaceRepository {
    suspend fun current(parent: CollaborationNativeParent): AppResult<CollaborationSpaceObservation>

    suspend fun contacts(
        owner: CollaborationSpaceOwner,
        query: String,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationSpaceContactPage>
}
