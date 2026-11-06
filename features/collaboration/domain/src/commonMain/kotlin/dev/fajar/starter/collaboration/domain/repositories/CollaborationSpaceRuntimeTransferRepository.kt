package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** One request; caller owns publication preflight, confirmed local gating and current admission. */
interface CollaborationSpaceRuntimeTransferRepository {
    /** Receipt identifies the original legacy device, version1; never a target-device revision. */
    suspend fun freeze(
        owner: CollaborationSpaceOwner,
        transfer: CollaborationSpaceRuntimeTransfer,
    ): AppResult<CollaborationSetupReceipt>

    suspend fun drain(
        owner: CollaborationSpaceOwner,
        transfer: CollaborationSpaceRuntimeTransfer,
    ): AppResult<CollaborationSpaceRuntimeDrain>
}
