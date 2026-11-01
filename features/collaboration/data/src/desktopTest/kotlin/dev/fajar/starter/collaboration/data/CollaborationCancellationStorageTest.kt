package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationCancellationStorageTest : CollaborationCancellationStorageContract() {
    override fun databaseOwner(): CollaborationListDatabaseOwner {
        val directory = Files.createTempDirectory("collaboration-cancellation").toFile()
        return CollaborationListDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
