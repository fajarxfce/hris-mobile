@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.uuid.Uuid

class BrowserCollaborationHistoryLogicalReadTest : CollaborationHistoryLogicalReadContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val namespace = "collaboration-history-logical-read-${Uuid.random()}"
        return CollaborationHistoryDatabaseOwner({ createAppDatabase(namespace) })
    }
}
