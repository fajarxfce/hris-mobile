@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import androidx.room.Room
import dev.fajar.starter.database.RoomAppDatabase
import dev.fajar.starter.database.StarterDatabase
import kotlin.uuid.Uuid
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleCollaborationSpaceIncomingDemandStorageTest :
    CollaborationSpaceIncomingDemandStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val directory =
            NSTemporaryDirectory().trimEnd('/') + "/space-incoming-demand-${Uuid.random()}"
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        return CollaborationHistoryDatabaseOwner(
            {
                RoomAppDatabase(
                    Room.databaseBuilder<StarterDatabase>("$directory/incoming-demand.db")
                )
            },
            { check(files.removeItemAtPath(directory, null)) },
        )
    }
}
