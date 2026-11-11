package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.RecoverCollaborationSpaceOriginalIncomingProvider
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.flow

internal class SpaceOriginalProviderRecoveryFixture {
    val transition = SpaceOriginalProviderTransitionFixture()
    val provider = transition.provider
    val base = transition.base
    var room =
        provider.request.copy(
            captured = transition.captured.copy(phase = CollaborationSpaceIncomingPhase.Pending)
        )
    var pending = emptyList<CollaborationHistoryReference>()
    var malformedPage: CollaborationHistoryReferences? = null
    var emptyFlow = false
    val history =
        object : CollaborationHistoryTraversalRepository by provider.history {
            override fun observeReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ) =
                flow<AppResult<CollaborationHistoryReferences>> {
                    assertEquals(provider.companyAccess, access)
                    assertEquals(provider.traversal, traversal)
                    assertNull(after)
                    assertEquals(1, limit)
                    provider.calls += "pending"
                    provider.hooks["pending"]?.invoke()
                    if (!emptyFlow)
                        emit(
                            provider.failures["pending"]?.let { AppResult.Failed(it) }
                                ?: AppResult.Success(
                                    malformedPage
                                        ?: CollaborationHistoryReferences(
                                            provider.progress,
                                            pending,
                                            null,
                                        )
                                )
                        )
                }
        }
    val recover =
        RecoverCollaborationSpaceOriginalIncomingProvider(
            transition.demands,
            provider.metadata,
            provider.registrations,
            history,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.sync,
            base.native.clock,
        )

    init {
        capture(room.captured)
    }

    fun capture(captured: CollaborationSpaceIncomingRoomDemand) {
        room = room.copy(captured = captured)
        base.demand(assertNotNull(base.snapshot).demand.copy(rooms = listOf(captured)))
    }

    fun head(resolved: Boolean = true) =
        CollaborationHistoryReference(
            0,
            CollaborationProviderHistoryEvent(
                requireNotNull(room.captured.headEventId),
                base.source.matrixUserId,
                0,
                "m.room.encrypted",
            ),
            resolved,
        )

    suspend fun run() = recover(base.runtime, room)
}
