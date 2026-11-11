@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.uuid.Uuid

class BrowserCollaborationSpaceOriginalRoomMetadataStorageTest :
    CollaborationSpaceOriginalRoomMetadataStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val namespace = "collaboration-space-original-room-metadata-${Uuid.random()}"
        return CollaborationHistoryDatabaseOwner({ createAppDatabase(namespace) })
    }
}
