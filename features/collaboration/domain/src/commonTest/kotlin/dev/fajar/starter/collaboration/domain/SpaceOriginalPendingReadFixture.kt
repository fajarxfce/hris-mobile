package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceOriginalIncomingReferences
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.flow

internal class SpaceOriginalPendingReadFixture {
    val transition = SpaceOriginalProviderTransitionFixture()
    val provider = transition.provider
    val base = transition.base
    var room =
        provider.request.copy(
            captured = transition.captured.copy(phase = CollaborationSpaceIncomingPhase.Pending)
        )
    var events: List<CollaborationHistoryReference> = List(2) { reference(it) }
    var next: String? = null
    var page: CollaborationHistoryReferences? = null
    var emptyFlow = false
    val requests = mutableListOf<Pair<String?, Int>>()
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
                    assertEquals(16, limit)
                    requests += after to limit
                    provider.calls += "pending"
                    provider.hooks["pending"]?.invoke()
                    if (!emptyFlow)
                        emit(
                            provider.failures["pending"]?.let { AppResult.Failed(it) }
                                ?: AppResult.Success(
                                    page
                                        ?: CollaborationHistoryReferences(
                                            provider.progress,
                                            events,
                                            next,
                                        )
                                )
                        )
                }
        }
    val read =
        ReadCollaborationSpaceOriginalIncomingReferences(
            transition.demands,
            provider.metadata,
            provider.registrations,
            history,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.sync,
        )

    init {
        capture(room.captured)
    }

    fun capture(captured: CollaborationSpaceIncomingRoomDemand) {
        room = room.copy(captured = captured)
        base.demand(assertNotNull(base.snapshot).demand.copy(rooms = listOf(captured)))
    }

    fun reference(sequence: Int) =
        CollaborationHistoryReference(
            sequence.toLong(),
            CollaborationProviderHistoryEvent(
                "\$pending-$sequence",
                base.source.matrixUserId,
                sequence.toLong(),
                "m.room.encrypted",
            ),
        )

    suspend fun run() = read(base.runtime, room)
}
