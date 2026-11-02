package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationNotificationSettingsStorageTest :
    CollaborationNotificationSettingsStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val directory = Files.createTempDirectory("collaboration-notifications").toFile()
        return CollaborationHistoryDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
