@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryPurpose
import dev.fajar.starter.database.createAppDatabase
import kotlin.uuid.Uuid

class BrowserCollaborationIncomingHistoryStorageTest :
    CollaborationHistoryTraversalStorageContract() {
    override val purpose = CollaborationHistoryPurpose.Incoming

    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val namespace = "collaboration-incoming-history-${Uuid.random()}"
        return CollaborationHistoryDatabaseOwner({ createAppDatabase(namespace) })
    }
}
