package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationSpaceReceiptStorageTest : CollaborationSpaceReceiptStorageContract() {
    override fun databaseOwner(): CollaborationReceiptDatabaseOwner {
        val directory = Files.createTempDirectory("collaboration-space-receipts").toFile()
        return CollaborationReceiptDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
