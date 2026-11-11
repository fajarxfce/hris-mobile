package dev.fajar.starter.app.di

import dev.fajar.starter.app.work.CollaborationSpaceOriginalIncomingTurn
import dev.fajar.starter.collaboration.domain.usecases.*
import org.koin.dsl.module

/** Typed Original orchestration belongs to the existing incoming worker's application lifetime. */
internal fun applicationCollaborationSpaceOriginalIncomingTurnModule() = module {
    factory {
        val room = inject<LoadCollaborationSpaceOriginalIncomingRoom>()
        val demand = inject<ReadCollaborationSpaceIncomingDemand>()
        val page = inject<AcquireCollaborationSpaceOriginalProviderHistory>()
        val advance = inject<AdvanceCollaborationSpaceOriginalIncomingProvider>()
        val references = inject<ReadCollaborationSpaceOriginalIncomingReferences>()
        val reconcile = inject<ReconcileCollaborationSpaceOriginalHistoryReferences>()
        val events = inject<AcquireCollaborationSpaceOriginalEvents>()
        val complete = inject<CompleteCollaborationSpaceOriginalIncomingReferences>()
        val retire = inject<CompleteCollaborationSpaceOriginalIncomingRoom>()
        val recover = inject<RecoverCollaborationSpaceOriginalIncomingProvider>()
        CollaborationSpaceOriginalIncomingTurn(
            room = { runtime, captured -> room.value(runtime, captured) },
            readDemand = { runtime -> demand.value(runtime) },
            acquirePage = { runtime, captured -> page.value(runtime, captured) },
            advance = { acquired -> advance.value(acquired) },
            readReferences = { runtime, captured -> references.value(runtime, captured) },
            reconcile = { runtime, captured, pending ->
                reconcile.value(runtime, captured, pending)
            },
            acquireEvents = { runtime, captured, ids -> events.value(runtime, captured, ids) },
            completeReferences = { runtime, captured, pending, batch ->
                complete.value(runtime, captured, pending, batch)
            },
            completeRoom = { runtime, captured -> retire.value(runtime, captured) },
            recoverProvider = { runtime, captured -> recover.value(runtime, captured) },
        )
    }
}
