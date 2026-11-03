@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleCommandPreventionTest {
    @Test fun identityAndOwnership() = withDatabase { verifyAbsentArchiveIdentityAndOwnership(it) }

    @Test
    fun conflictsAndCapacity() = withDatabase { verifyAbsentArchiveActiveConflictAndCapacity(it) }

    private fun withDatabase(block: suspend (AppDatabase) -> Unit) = runTest {
        val directory = NSTemporaryDirectory().trimEnd('/') + "/command-prevention-${Uuid.random()}"
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val database =
            RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$directory/prevention.db"))
        try {
            block(database)
        } finally {
            database.close()
            check(files.removeItemAtPath(directory, null))
        }
    }
}
