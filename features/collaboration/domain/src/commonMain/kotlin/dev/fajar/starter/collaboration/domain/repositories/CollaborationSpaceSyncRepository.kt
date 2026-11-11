package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Exact installed-client synchronization. Admission belongs to the owning use case. */
interface CollaborationSpaceSyncRepository {
    /**
     * Starts the original client's owned sync and waits for its first response. Reuses the
     * persisted cursor; never opens a store, joins rooms, enables message queues or registers
     * another client. Cancelling this waiter does not abandon native I/O; the runtime owns drain.
     */
    suspend fun synchronize(runtime: CollaborationSpaceRuntimeReady): AppResult<Unit>

    /** Current original store identity only; no acquisition, credential renewal or transport. */
    fun incomingSource(runtime: CollaborationSpaceRuntimeReady): CollaborationSpaceIncomingSource?

    /**
     * Observe the already acquired sync. Never starts or retries it. The flow ends when this exact
     * runtime is retired; the application owns cancellation and serial durable admission.
     * Latest-response conflation is explicit through each response's own previous/next positions.
     */
    fun observeBatches(
        runtime: CollaborationSpaceRuntimeReady
    ): Flow<AppResult<CollaborationSpaceSyncBatch>>
}
