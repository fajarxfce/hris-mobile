package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationNotificationPublicationStorageTest :
    CollaborationNotificationPublicationStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val directory = Files.createTempDirectory("publication-journal").toFile()
        return CollaborationHistoryDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
