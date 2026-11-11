@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import androidx.room.Room
import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryPurpose
import dev.fajar.starter.database.RoomAppDatabase
import dev.fajar.starter.database.StarterDatabase
import kotlin.uuid.Uuid
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleCollaborationIncomingHistoryStorageTest :
    CollaborationHistoryTraversalStorageContract() {
    override val purpose = CollaborationHistoryPurpose.Incoming

    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val directory =
            NSTemporaryDirectory().trimEnd('/') + "/company-incoming-history-${Uuid.random()}"
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        return CollaborationHistoryDatabaseOwner(
            {
                RoomAppDatabase(
                    Room.databaseBuilder<StarterDatabase>("$directory/company-incoming-history.db")
                )
            },
            { check(files.removeItemAtPath(directory, null)) },
        )
    }
}
