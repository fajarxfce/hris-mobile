package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AdvanceCollaborationSpaceOriginalIncomingProvider
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class SpaceOriginalProviderTransitionFixture(
    exhausted: Boolean = true,
    phase: CollaborationSpaceIncomingPhase = CollaborationSpaceIncomingPhase.Latest,
) {
    val provider = SpaceOriginalProviderHistoryFixture().also { it.phase(phase) }
    val base = provider.base
    val captured = base.captured
    val previous =
        if (phase == CollaborationSpaceIncomingPhase.Provider)
            CollaborationHistoryProgress(
                base.id(110),
                0,
                "earlier",
                0,
                CollaborationIncomingHistoryPosition("start", null),
            )
        else null
    val checkpoint =
        CollaborationHistoryProgress(
            base.id(110),
            previous?.revision?.plus(1) ?: 0,
            if (exhausted) null else "next",
            2,
            CollaborationIncomingHistoryPosition("start", null),
        )
    var receipt =
        CollaborationSpaceOriginalProviderHistoryAcquisition(
            base.runtime,
            provider.request,
            if (phase == CollaborationSpaceIncomingPhase.Latest)
                CollaborationHistoryPageOrigin.Latest
            else CollaborationHistoryPageOrigin.SavedPosition,
            previous,
            checkpoint,
            2,
        )
    var writes = 0
    var commit = true
    var lostReply = false
    var fabricatedReply = false
    val demands =
        object : CollaborationSpaceIncomingDemandRepository by provider.demands {
            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceIncomingDemandSnapshot?,
                demand: CollaborationSpaceIncomingDemand,
                wake: CollaborationSpaceIncomingWake?,
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(base.access, access)
                assertNull(wake, "Provider advancement cannot acknowledge or replace the wake")
                writes++
                provider.calls += "write"
                provider.hooks["write"]?.invoke()
                if (base.snapshot != expected) return AppResult.Success(null)
                val next = CollaborationSpaceIncomingDemandSnapshot(base.id(120 + writes), demand)
                if (commit) base.snapshot = next
                provider.calls += "written"
                provider.hooks["written"]?.invoke()
                if (lostReply) return AppResult.Failed(base.directory.storageFailure)
                return AppResult.Success(if (commit || fabricatedReply) next else null)
            }
        }
    val advance =
        AdvanceCollaborationSpaceOriginalIncomingProvider(
            demands,
            provider.metadata,
            provider.registrations,
            provider.history,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.sync,
        )

    init {
        assertTrue(validCollaborationSpaceOriginalProviderAcquisition(receipt))
        provider.progress = checkpoint
    }

    suspend fun run() = advance(receipt)

    fun room() = assertNotNull(base.snapshot).demand.rooms.single { it.target == captured.target }

    fun independentProducer() {
        val demand = assertNotNull(base.snapshot).demand
        base.demand(demand.copy(sources = demand.sources.map { it.copy(position = "later") }))
    }
}
