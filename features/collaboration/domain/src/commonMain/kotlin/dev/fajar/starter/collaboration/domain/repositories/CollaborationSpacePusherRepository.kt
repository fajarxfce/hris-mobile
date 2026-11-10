package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceRuntimeReady
import dev.fajar.starter.common.result.AppResult

/** Managed registration through an exact installed group runtime; domain owns current admission. */
interface CollaborationSpacePusherRepository {
    /**
     * Uses the current SDK bearer for one fixed empty request. No enrollment, token refresh, queue
     * mutation or second client; the runtime owns cancellation and request drain.
     */
    suspend fun activate(runtime: CollaborationSpaceRuntimeReady): AppResult<Unit>
}
