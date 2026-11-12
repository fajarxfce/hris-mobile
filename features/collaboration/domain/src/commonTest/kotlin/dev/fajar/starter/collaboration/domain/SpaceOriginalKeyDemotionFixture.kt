package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.DemoteCollaborationSpaceOriginalIncomingRoom
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class SpaceOriginalKeyDemotionFixture {
    val queue = SpaceOriginalQueueCompletionFixture()
    val pending = queue.pending
    val provider = queue.provider
    val base = queue.base
    var writes = 0
    var commit = true
    var lostReply = false
    var fabricatedReply = false
    var response:
        ((CollaborationSpaceIncomingDemandSnapshot) -> CollaborationSpaceIncomingDemandSnapshot)? =
        null
    var requested: CollaborationSpaceIncomingWake? = null
    var wake = CollaborationSpaceIncomingWake(base.id(970))
    val demands =
        object : CollaborationSpaceIncomingDemandRepository by provider.demands {
            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceIncomingDemandSnapshot?,
                demand: CollaborationSpaceIncomingDemand,
                wake: CollaborationSpaceIncomingWake?,
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(base.access, access)
                assertNotNull(wake)
                requested = wake
                writes++
                provider.calls += "write"
                provider.hooks["write"]?.invoke()
                if (base.snapshot != expected) return AppResult.Success(null)
                val next = CollaborationSpaceIncomingDemandSnapshot(base.id(980 + writes), demand)
                if (commit) {
                    base.snapshot = next
                    this@SpaceOriginalKeyDemotionFixture.wake = wake
                }
                provider.calls += "written"
                provider.hooks["written"]?.invoke()
                if (lostReply) return AppResult.Failed(base.directory.storageFailure)
                return AppResult.Success(
                    if (commit || fabricatedReply) response?.invoke(next) ?: next else null
                )
            }
        }
    val demote =
        DemoteCollaborationSpaceOriginalIncomingRoom(
            demands,
            provider.metadata,
            provider.registrations,
            pending.history,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.sync,
        )

    init {
        val head = pending.head(resolved = false)
        pending.pending = listOf(head)
        provider.references[head.event.eventId] = head
    }

    suspend fun run() = demote(base.runtime, pending.room)

    fun retainedTask() =
        requireNotNull(base.snapshot).demand.rooms.singleOrNull {
            it.target == pending.room.captured.target
        }

    fun recovery() = requireNotNull(base.snapshot).demand.historyRecovery
}
