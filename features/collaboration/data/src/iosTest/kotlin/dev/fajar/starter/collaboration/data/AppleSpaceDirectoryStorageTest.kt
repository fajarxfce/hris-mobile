@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import androidx.room.Room
import dev.fajar.starter.database.RoomAppDatabase
import dev.fajar.starter.database.StarterDatabase
import kotlin.uuid.Uuid
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

private fun appleSpaceDirectoryDatabase(): SpaceDirectoryDatabaseOwner {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/space-directory-${Uuid.random()}"
    val files = NSFileManager.defaultManager
    check(files.createDirectoryAtPath(directory, true, null, null))
    return SpaceDirectoryDatabaseOwner(
        { RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$directory/directory.db")) },
        { check(files.removeItemAtPath(directory, null)) },
    )
}

class AppleSpaceDirectoryStorageTest : SpaceDirectoryStorageContract() {
    override fun databaseOwner() = appleSpaceDirectoryDatabase()
}

class AppleSpaceDirectoryAdmissionStorageTest : SpaceDirectoryAdmissionStorageContract() {
    override fun databaseOwner() = appleSpaceDirectoryDatabase()
}
