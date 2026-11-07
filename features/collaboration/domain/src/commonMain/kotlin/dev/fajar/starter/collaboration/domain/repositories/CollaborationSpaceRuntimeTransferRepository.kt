package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** One request per method; caller owns private-store gating, freshness and current admission. */
interface CollaborationSpaceRuntimeTransferRepository {
    /** One fresh provider observation before local staging; no freeze, upload or activation. */
    suspend fun publication(
        owner: CollaborationSpaceOwner,
        transfer: CollaborationSpaceRuntimeTransfer,
    ): AppResult<CollaborationSpaceLegacyPublication>

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
