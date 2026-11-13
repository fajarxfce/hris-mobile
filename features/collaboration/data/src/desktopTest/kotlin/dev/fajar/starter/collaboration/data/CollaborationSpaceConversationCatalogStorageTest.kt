package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationSpaceConversationCatalogStorageTest :
    CollaborationSpaceConversationCatalogStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val directory =
            Files.createTempDirectory("collaboration-space-conversation-catalog").toFile()
        return CollaborationHistoryDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
