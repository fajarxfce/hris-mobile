package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceRuntimeReady
import dev.fajar.starter.common.result.AppResult

/** Exact installed-client synchronization. Admission belongs to the owning use case. */
interface CollaborationSpaceSyncRepository {
    /**
     * Starts the original client's owned sync and waits for its first response. Reuses the
     * persisted cursor; never opens a store, joins rooms, enables message queues or registers
     * another client. Cancelling this waiter does not abandon native I/O; the runtime owns drain.
     */
    suspend fun synchronize(runtime: CollaborationSpaceRuntimeReady): AppResult<Unit>
}
