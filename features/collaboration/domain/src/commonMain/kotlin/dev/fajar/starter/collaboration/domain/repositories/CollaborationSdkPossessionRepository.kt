package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.entities.CollaborationSdkPossession
import dev.fajar.starter.common.result.AppResult

interface CollaborationSdkPossessionRepository {
    /** Null means required local files/credentials are absent; it never permits enrollment. */
    suspend fun readPrivateIdentity(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationSdkPossession?>
}
