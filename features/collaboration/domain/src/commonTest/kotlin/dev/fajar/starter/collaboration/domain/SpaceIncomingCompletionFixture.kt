package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.CompleteCollaborationSpaceIncomingRoom
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals

internal class SpaceIncomingCompletionFixture {
    val request =
        SpaceIncomingRequestFixture().also {
            it.queued()
            it.covered()
        }
    val access = request.access
    val key = request.key
    val captured = request.snapshot!!.demand.rooms.single()
    val originalWake = request.wake
    var latest = request.pending.progress
    val history =
        object : CollaborationSpaceHistoryTraversalRepository by request.history {
            override suspend fun progress(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ): AppResult<CollaborationHistoryProgress?> {
                assertEquals(this@SpaceIncomingCompletionFixture.access, access)
                assertEquals(key, traversal)
                return request.base.read("progress") { latest }
            }
        }
    val demands = request.demands
    val useCase =
        CompleteCollaborationSpaceIncomingRoom(
            demands,
            request.base.bindings,
            history,
            request.base.directory.directory,
            request.base.native.setup.sessions,
            request.base.native.server,
        )

    suspend fun complete() = useCase(access, key, captured)
}
