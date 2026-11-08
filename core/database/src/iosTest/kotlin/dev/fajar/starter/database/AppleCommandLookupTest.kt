@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleCommandLookupTest {
    @Test fun activeToArchive() = withDatabase { verifyCommandLookupObservation(it) }

    @Test fun absentToArchive() = withDatabase { verifyCommandLookupPrevention(it) }

    @Test fun ownership() = withDatabase { verifyCommandLookupOwnership(it) }

    private fun withDatabase(block: suspend (AppDatabase) -> Unit) = runTest {
        val directory = NSTemporaryDirectory().trimEnd('/') + "/command-lookup-${Uuid.random()}"
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val database =
            RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$directory/lookup.db"))
        try {
            block(database)
        } finally {
            database.close()
            check(files.removeItemAtPath(directory, null))
        }
    }
}
