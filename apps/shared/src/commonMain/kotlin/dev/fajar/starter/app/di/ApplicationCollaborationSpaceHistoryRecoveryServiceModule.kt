package dev.fajar.starter.app.di

import dev.fajar.starter.app.work.CollaborationSpaceHistoryRecoveryService
import dev.fajar.starter.collaboration.domain.usecases.*
import org.koin.dsl.module

/** No additional scope/client/scheduler: the incoming worker invokes this bounded service. */
internal fun applicationCollaborationSpaceHistoryRecoveryServiceModule() = module {
    factory {
        val select = inject<ReadCollaborationSpaceHistoryRecoveryTurn>()
        val original = inject<LoadCollaborationSpaceOriginalKeyHistory>()
        val published = inject<LoadCollaborationSpaceKeyHistory>()
        val readOriginal = inject<ReadCollaborationSpaceOriginalKeyReferences>()
        val readPublished = inject<ReadCollaborationSpaceKeyReferences>()
        val acquireOriginal = inject<AcquireCollaborationSpaceOriginalEvents>()
        val acquirePublished = inject<AcquireCollaborationSpaceEvents>()
        val completeOriginal = inject<CompleteCollaborationSpaceOriginalKeyReferences>()
        val completePublished = inject<CompleteCollaborationSpaceKeyReferences>()
        val complete = inject<CompleteCollaborationSpaceHistoryRecoveryTurn>()
        val defer = inject<DeferCollaborationSpaceHistoryRecoveryTurn>()
        CollaborationSpaceHistoryRecoveryService(
            select = { runtime -> select.value(runtime) },
            original = { runtime, source, company, locator ->
                original.value(runtime, source, company, locator)
            },
            published = { runtime, source, locator -> published.value(runtime, source, locator) },
            readOriginal = { runtime, history, generation ->
                readOriginal.value(runtime, history, generation)
            },
            readPublished = { runtime, history, generation ->
                readPublished.value(runtime, history, generation)
            },
            acquireOriginal = { runtime, history, references ->
                acquireOriginal.value(runtime, history, references)
            },
            acquirePublished = { runtime, history, references ->
                acquirePublished.value(runtime, history, references)
            },
            completeOriginal = { runtime, history, references, batch ->
                completeOriginal.value(runtime, history, references, batch)
            },
            completePublished = { runtime, history, references, batch ->
                completePublished.value(runtime, history, references, batch)
            },
            complete = { turn, attempt -> complete.value(turn, attempt) },
            defer = { turn, failure -> defer.value(turn, failure) },
        )
    }
}
