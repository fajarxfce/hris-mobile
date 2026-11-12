package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Original store I/O and logical SDK lifetime. The owning use case admits every live operation. */
interface CollaborationSpaceRuntimeRepository {
    /**
     * Existing exact client only. Null before publication/after retirement; a different instance ID
     * on recreation, including the same logical owner/device/bootstrap. No acquisition or I/O.
     * Observation owns no client lifetime and retained local facts do not depend on this stream.
     */
    fun observePublication(
        runtime: CollaborationSpaceRuntimeReady
    ): Flow<AppResult<CollaborationSpaceRuntimePublication?>> = flowOf(AppResult.Success(null))

    /** Bounded callbacks from installed native clients only; no acquisition or wake scheduling. */
    fun spaceReadinessSignals(): Flow<CollaborationSpaceReadinessSignal> = emptyFlow()

    /**
     * Holds the existing paused writer for an exact logical runtime and known original room. Does
     * not open a store, join, sync, enqueue or grant server admission. The owning use case must
     * freshly admit the published organization room before settlement or delivery.
     */
    suspend fun acquireOriginalQueue(
        runtime: CollaborationSpaceRuntimeReady,
        matrixRoomId: String,
    ): AppResult<CollaborationSpaceOriginalQueueLease>

    /**
     * Complete paused queue facts from an already installed exact runtime and known room. Never
     * opens a store, joins, synchronizes, enqueues, settles or resumes. The owning use case must
     * admit publication; an unavailable live runtime never invalidates retained offline history.
     */
    suspend fun inspectOriginalQueue(
        runtime: CollaborationSpaceRuntimeReady,
        matrixRoomId: String,
    ): AppResult<CollaborationSpaceOriginalQueueSnapshot>

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
