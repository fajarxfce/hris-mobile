@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.uuid.Uuid

class BrowserCollaborationSpaceEditInboxStorageTest : CollaborationSpaceEditInboxStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val namespace = "collaboration-space-edit-inbox-${Uuid.random()}"
        return CollaborationHistoryDatabaseOwner({ createAppDatabase(namespace) })
    }
}
