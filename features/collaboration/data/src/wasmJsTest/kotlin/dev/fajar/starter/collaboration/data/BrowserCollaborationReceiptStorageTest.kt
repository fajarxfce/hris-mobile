@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.uuid.Uuid

class BrowserCollaborationReceiptStorageTest : CollaborationReceiptStorageContract() {
    override fun databaseOwner(): CollaborationReceiptDatabaseOwner {
        val namespace = "collaboration-receipts-${Uuid.random()}"
        return CollaborationReceiptDatabaseOwner({ createAppDatabase(namespace) })
    }
}
