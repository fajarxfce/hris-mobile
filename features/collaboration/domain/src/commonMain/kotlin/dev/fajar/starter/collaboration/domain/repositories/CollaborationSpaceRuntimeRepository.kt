package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.StateFlow

/** Original store I/O and logical SDK lifetime. The owning use case admits every live operation. */
interface CollaborationSpaceRuntimeRepository {
    suspend fun isSpaceOpen(
        owner: CollaborationSpaceOwner,
        deviceId: String,
        operationId: String,
    ): AppResult<Boolean>

    /**
     * Uses existing original files/keys and a confirmed durable bootstrap. Returns an unpublished,
     * paused application-owned runtime; never enrolls, creates a store, starts sync or sends.
     */
    suspend fun installOriginal(
        owner: CollaborationSpaceOwner,
        original: CollaborationExistingRuntimeHandover,
        setup: CollaborationSpaceSetupRecord,
    ): AppResult<CollaborationSpaceRuntimeLease>

    /**
     * Stops only this exact logical owner; original protected facts and queue identities remain.
     */
    suspend fun closeSpace(owner: CollaborationSpaceOwner): AppResult<Unit>
}

interface CollaborationSpaceRuntimeLease {
    val owner: CollaborationSpaceOwner
    val deviceId: String
    val installedOperationId: String
    val credentialFailures: StateFlow<Failure?>

    /** Synchronous acceptance only after the owning use case revalidates current admission. */
    fun publish(): AppResult<Unit>

    suspend fun retryCredentialWrite(): AppResult<Unit>

    suspend fun close(): AppResult<Unit>
}
