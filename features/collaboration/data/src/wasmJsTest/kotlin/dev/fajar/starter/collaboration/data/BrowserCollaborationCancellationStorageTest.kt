@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.uuid.Uuid

class BrowserCollaborationCancellationStorageTest : CollaborationCancellationStorageContract() {
    override fun databaseOwner(): CollaborationListDatabaseOwner {
        val namespace = "collaboration-cancellation-${Uuid.random()}"
        return CollaborationListDatabaseOwner({ createAppDatabase(namespace) })
    }
}
