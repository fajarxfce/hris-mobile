@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import androidx.room.Room
import dev.fajar.starter.database.RoomAppDatabase
import dev.fajar.starter.database.StarterDatabase
import kotlin.uuid.Uuid
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleCollaborationSpaceReceiptStorageTest : CollaborationSpaceReceiptStorageContract() {
    override fun databaseOwner(): CollaborationReceiptDatabaseOwner {
        val directory = NSTemporaryDirectory().trimEnd('/') + "/space-receipt-${Uuid.random()}"
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        return CollaborationReceiptDatabaseOwner(
            { RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$directory/receipts.db")) },
            { check(files.removeItemAtPath(directory, null)) },
        )
    }
}
