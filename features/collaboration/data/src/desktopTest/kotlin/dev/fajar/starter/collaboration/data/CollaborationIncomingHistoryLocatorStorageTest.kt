package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationIncomingHistoryLocatorStorageTest :
    CollaborationIncomingHistoryLocatorStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val directory = Files.createTempDirectory("incoming-history-locators").toFile()
        return CollaborationHistoryDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
