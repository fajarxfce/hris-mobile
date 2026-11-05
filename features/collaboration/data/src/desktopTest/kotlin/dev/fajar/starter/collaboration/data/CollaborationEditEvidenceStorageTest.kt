package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationEditEvidenceStorageTest : CollaborationEditEvidenceStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val directory = Files.createTempDirectory("collaboration-edit-evidence").toFile()
        return CollaborationHistoryDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
