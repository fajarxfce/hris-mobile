@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import androidx.room.Room
import dev.fajar.starter.database.RoomAppDatabase
import dev.fajar.starter.database.StarterDatabase
import kotlin.uuid.Uuid
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleCollaborationIncomingHistoryWaveStorageTest :
    CollaborationIncomingHistoryWaveStorageContract() {
    override fun databaseOwner(): CollaborationHistoryDatabaseOwner {
        val directory =
            NSTemporaryDirectory().trimEnd('/') + "/company-incoming-wave-${Uuid.random()}"
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        return CollaborationHistoryDatabaseOwner(
            {
                RoomAppDatabase(
                    Room.databaseBuilder<StarterDatabase>("$directory/company-incoming-wave.db")
                )
            },
            { check(files.removeItemAtPath(directory, null)) },
        )
    }
}
