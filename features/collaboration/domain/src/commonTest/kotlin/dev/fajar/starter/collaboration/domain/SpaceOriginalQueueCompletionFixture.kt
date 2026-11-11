package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.CompleteCollaborationSpaceOriginalIncomingRoom
import kotlin.test.*

internal class SpaceOriginalQueueCompletionFixture {
    val pending = SpaceOriginalProviderRecoveryFixture()
    val provider = pending.provider
    val base = pending.base
    val transition = pending.transition
    val complete =
        CompleteCollaborationSpaceOriginalIncomingRoom(
            transition.demands,
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
        val head = pending.head()
        provider.references[head.event.eventId] = head
    }

    suspend fun run() = complete(base.runtime, pending.room)

    fun retainedTask() =
        assertNotNull(base.snapshot).demand.rooms.singleOrNull {
            it.target == pending.room.captured.target
        }
}
