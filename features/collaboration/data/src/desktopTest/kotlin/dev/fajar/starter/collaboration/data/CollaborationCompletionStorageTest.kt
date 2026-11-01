package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationCompletionStorageTest : CollaborationCompletionStorageContract() {
    override fun databaseOwner(): CollaborationListDatabaseOwner {
        val directory = Files.createTempDirectory("collaboration-completion").toFile()
        return CollaborationListDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
