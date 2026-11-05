@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.uuid.Uuid

class BrowserCollaborationListSyncStorageTest : CollaborationListSyncStorageContract() {
    override fun databaseOwner(): CollaborationListDatabaseOwner {
        val namespace = "collaboration-list-sync-${Uuid.random()}"
        return CollaborationListDatabaseOwner({ createAppDatabase(namespace) })
    }
}
