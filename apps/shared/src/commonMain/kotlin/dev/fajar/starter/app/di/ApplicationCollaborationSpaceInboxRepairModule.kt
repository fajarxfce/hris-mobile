package dev.fajar.starter.app.di

import dev.fajar.starter.app.work.CollaborationSpaceInboxHistoryTurnWorker
import dev.fajar.starter.app.work.CollaborationSpaceInboxRepairWorker
import dev.fajar.starter.collaboration.domain.usecases.*
import org.koin.dsl.module

/**
 * Local account/Group ownership only. Registering this factory starts no collector or transport.
 */
internal fun applicationCollaborationSpaceInboxRepairModule() = module {
    factory {
        val progress = inject<ReadCollaborationSpaceInboxHistoryProgress>()
        val change = inject<ChangeCollaborationSpaceInboxHistoryProgress>()
        val catalog = inject<ReadCollaborationSpaceConversationCatalog>()
        val sources = inject<ReadCollaborationSpaceInboxHistorySources>()
        val source = inject<ReadCollaborationSpaceInboxHistorySource>()
        val prepare = inject<PrepareCollaborationSpaceInboxHistorySource>()
        val history = inject<ReadCollaborationSpaceHistoryChanges>()
        val confirm = inject<ConfirmCollaborationSpaceHistoryChanges>()
        val publish = inject<PublishCollaborationSpaceInboxHistory>()
        val retain = inject<RetainCollaborationSpaceInboxHistoryChanges>()
        val restart = inject<RestartCollaborationSpaceInboxHistorySource>()
        val pending = inject<ReadCollaborationSpaceInboxHistoryPending>()
        val completePending = inject<CompleteCollaborationSpaceInboxHistoryPending>()
        val retainPending = inject<RetainCollaborationSpaceInboxHistoryPending>()
        CollaborationSpaceInboxHistoryTurnWorker(
            readProgress = { progress.value(it) },
            changeProgress = { access, expected, action -> change.value(access, expected, action) },
            readCatalog = { access, after, limit -> catalog.value(access, after, limit) },
            readSources = { access, after, limit -> sources.value(access, after, limit) },
            readSource = { access, conversation -> source.value(access, conversation) },
            prepareSource = { access, conversation, pass ->
                prepare.value(access, conversation, pass)
            },
            readHistory = { access, conversation, cursor, limit ->
                history.value(access, conversation, cursor, limit)
            },
            confirmHistory = { access, conversation, cursor, read ->
                confirm.value(access, conversation, cursor, read)
            },
            publishHistory = { access, conversation, ids ->
                publish.value(access, conversation, ids)
            },
            retainHistory = { access, expected, read, published ->
                retain.value(access, expected, read, published)
            },
            restartSource = { access, expected, read -> restart.value(access, expected, read) },
            readPending = { access, expected, limit -> pending.value(access, expected, limit) },
            completePending = { access, expected, read ->
                completePending.value(access, expected, read)
            },
            retainPending = { access, expected, read, published ->
                retainPending.value(access, expected, read, published)
            },
        )
    }
    factory {
        val messages = inject<ObserveCollaborationSpaceMessages>()
        val requests = inject<ObserveCollaborationSpaceInboxRepairSignals>()
        val progress = inject<ReadCollaborationSpaceInboxRepairProgress>()
        val change = inject<ChangeCollaborationSpaceInboxRepairProgress>()
        val page = inject<ReadCollaborationSpaceInboxRepairs>()
        val reference = inject<ReadCollaborationSpaceInboxRepair>()
        val reconcile = inject<ReconcileCollaborationSpaceInboxOriginal>()
        val seed = inject<SeedCollaborationSpaceInboxOriginal>()
        val historySignals = inject<ObserveCollaborationSpaceHistoryChanges>()
        val catalog = inject<ObserveCollaborationSpaceConversationCatalog>()
        val destination = inject<ObserveCollaborationSpaceInboxDestination>()
        val historyTurn = inject<CollaborationSpaceInboxHistoryTurnWorker>()
        CollaborationSpaceInboxRepairWorker(
            messages = { messages.value(it) },
            signals = { requests.value(it) },
            historySignals = { historySignals.value(it) },
            catalog = { catalog.value(it) },
            destination = { destination.value(it) },
            historyTurn = { access, frame, passId, recover ->
                historyTurn.value(access, frame.trigger, frame.destination, passId, recover)
            },
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
