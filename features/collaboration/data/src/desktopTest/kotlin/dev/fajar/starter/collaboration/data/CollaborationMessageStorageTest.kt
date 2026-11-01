package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationMessageStorageTest : CollaborationMessageStorageContract() {
    override fun databaseOwner(): CollaborationListDatabaseOwner {
        val directory = Files.createTempDirectory("collaboration-message").toFile()
        return CollaborationListDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
