package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationSpaceInboxSeedStorageTest : CollaborationSpaceInboxSeedStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val directory = Files.createTempDirectory("collaboration-space-inbox-seed").toFile()
        return CollaborationHistoryDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
