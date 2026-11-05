@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.uuid.Uuid

class BrowserCollaborationEditEvidenceStorageTest : CollaborationEditEvidenceStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val namespace = "collaboration-edit-evidence-${Uuid.random()}"
        return CollaborationHistoryDatabaseOwner({ createAppDatabase(namespace) })
    }
}
