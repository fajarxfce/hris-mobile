package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Local observation from an already owned SDK. No enrollment, restore or server admission. */
interface CollaborationLocalDeviceIdentityRepository {
    /**
     * Reads public user/device/key identity while retaining the existing client's child owner. The
     * caller owns a finite request lifetime and must revalidate account/admission afterward.
     * Unavailable means no readable open client, never proof that no older store exists.
     */
    suspend fun readLocalIdentity(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationLocalDeviceIdentity>
}
