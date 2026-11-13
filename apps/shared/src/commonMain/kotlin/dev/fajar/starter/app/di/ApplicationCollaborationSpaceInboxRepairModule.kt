package dev.fajar.starter.app.di

import dev.fajar.starter.app.work.CollaborationSpaceInboxRepairWorker
import dev.fajar.starter.collaboration.domain.usecases.*
import org.koin.dsl.module

/**
 * Local account/Group ownership only. Registering this factory starts no collector or transport.
 */
internal fun applicationCollaborationSpaceInboxRepairModule() = module {
    factory {
        val messages = inject<ObserveCollaborationSpaceMessages>()
        val requests = inject<ObserveCollaborationSpaceInboxRepairDemand>()
        val progress = inject<ReadCollaborationSpaceInboxRepairProgress>()
        val change = inject<ChangeCollaborationSpaceInboxRepairProgress>()
        val page = inject<ReadCollaborationSpaceInboxRepairs>()
        val reference = inject<ReadCollaborationSpaceInboxRepair>()
        val reconcile = inject<ReconcileCollaborationSpaceInboxOriginal>()
        val seed = inject<SeedCollaborationSpaceInboxOriginal>()
        CollaborationSpaceInboxRepairWorker(
            messages = { messages.value(it) },
            requests = { requests.value(it) },
            readProgress = { progress.value(it) },
            changeProgress = { access, mutation -> change.value(access, mutation) },
            readPage = { access, after, limit -> page.value(access, after, limit) },
            readReference = { access, conversation, message ->
                reference.value(access, conversation, message)
            },
            seed = { access, original -> seed.value(access, original) },
            reconcile = { access, original -> reconcile.value(access, original) },
        )
    }
}
