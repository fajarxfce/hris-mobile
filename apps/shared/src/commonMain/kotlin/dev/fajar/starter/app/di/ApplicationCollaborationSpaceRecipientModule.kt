package dev.fajar.starter.app.di

import dev.fajar.starter.app.work.CollaborationSpaceRecipientTransferWorker
import dev.fajar.starter.collaboration.domain.usecases.*
import org.koin.dsl.module

/** Recipient transfer bindings; collection belongs to the local account/Group inbox owner. */
internal fun applicationCollaborationSpaceRecipientModule() = module {
    factory {
        val source = inject<ObserveCollaborationSpaceRecipientOutboxVersion>()
        val catalog = inject<ObserveCollaborationSpaceConversationCatalog>()
        val destination = inject<ObserveCollaborationSpaceInboxDestination>()
        val page = inject<ReadCollaborationSpaceRecipientPending>()
        val transfer = inject<TransferCollaborationSpaceRecipientSubmission>()
        CollaborationSpaceRecipientTransferWorker(
            source = { source.value(it) },
            catalog = { catalog.value(it) },
            destination = { destination.value(it) },
            readPage = { access, after, limit -> page.value(access, after, limit) },
            transfer = { access, submission -> transfer.value(access, submission) },
        )
    }
}
