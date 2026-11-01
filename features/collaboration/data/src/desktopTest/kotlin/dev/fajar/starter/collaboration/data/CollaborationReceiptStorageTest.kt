package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files

class CollaborationReceiptStorageTest : CollaborationReceiptStorageContract() {
    override fun databaseOwner(): CollaborationReceiptDatabaseOwner {
        val directory = Files.createTempDirectory("collaboration-receipts").toFile()
        return CollaborationReceiptDatabaseOwner(
            { createAppDatabase(directory) },
            {
                directory.deleteRecursively()
                Unit
            },
        )
    }
}
