package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.entities.CollaborationSdkInventory
import dev.fajar.starter.common.result.AppResult

/** Inspect the original namespace; never initialize, enroll, restore or replace a private store. */
interface CollaborationSdkInventoryRepository {
    suspend fun inspect(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationSdkInventory>
}
