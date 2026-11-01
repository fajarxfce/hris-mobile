@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.uuid.Uuid

class BrowserCollaborationListStorageTest : CollaborationListStorageContract() {
    override fun databaseOwner(): CollaborationListDatabaseOwner {
        val namespace = "collaboration-list-${Uuid.random()}"
        return CollaborationListDatabaseOwner({ createAppDatabase(namespace) })
    }
}
