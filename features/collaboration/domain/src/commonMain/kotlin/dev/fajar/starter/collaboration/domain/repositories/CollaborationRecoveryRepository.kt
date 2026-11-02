package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationRecoveryRepository {
    suspend fun devices(
        parent: CollaborationNativeParent,
        afterCompanyId: String?,
        limit: Int,
    ): AppResult<CollaborationDevicePage>

    suspend fun admission(owner: CollaborationOwner): AppResult<CollaborationAdmissionObservation>
}
