package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryPurpose
import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationIncomingHistoryStorageTest : CollaborationHistoryTraversalStorageContract() {
    override val purpose = CollaborationHistoryPurpose.Incoming

    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val directory = Files.createTempDirectory("collaboration-incoming-history").toFile()
        return CollaborationHistoryDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
