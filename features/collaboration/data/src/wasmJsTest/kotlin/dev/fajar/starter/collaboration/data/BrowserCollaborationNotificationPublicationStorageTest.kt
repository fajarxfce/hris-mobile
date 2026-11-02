@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.uuid.Uuid

class BrowserCollaborationNotificationPublicationStorageTest :
    CollaborationNotificationPublicationStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val namespace = "publication-journal-${Uuid.random()}"
        return CollaborationHistoryDatabaseOwner({ createAppDatabase(namespace) })
    }
}
