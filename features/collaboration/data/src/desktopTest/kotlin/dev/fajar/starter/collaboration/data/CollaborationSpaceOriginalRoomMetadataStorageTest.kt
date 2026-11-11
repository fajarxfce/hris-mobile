package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationSpaceOriginalRoomMetadataStorageTest :
    CollaborationSpaceOriginalRoomMetadataStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val directory =
            Files.createTempDirectory("collaboration-space-original-room-metadata").toFile()
        return CollaborationHistoryDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
