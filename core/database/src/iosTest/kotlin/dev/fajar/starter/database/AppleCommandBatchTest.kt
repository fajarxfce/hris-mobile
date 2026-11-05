@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleCommandBatchTest {
    @Test fun replayAndArchives() = withDatabase { verifyBatchReplayAndArchives(it) }

    @Test fun conflictsRollBack() = withDatabase { verifyBatchConflictRollsBack(it) }

    @Test fun ownershipAndBounds() = withDatabase { verifyBatchOwnershipAndBounds(it) }

    @Test fun capacity() = withDatabase { verifyBatchCapacity(it) }

    @Test fun racesAndCancellation() = withDatabase { verifyBatchRacesAndCancellation(it) }

    @Test
    fun replaySurvivesReopenAndSessionReplacement() = runTest {
        val directory =
            NSTemporaryDirectory().trimEnd('/') + "/command-batch-reopen-${Uuid.random()}"
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        fun open() = RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$directory/batch.db"))
        try {
            val first = open()
            try {
                verifyBatchReplayAndArchives(first)
            } finally {
                first.close()
            }
            val reopened = open()
            try {
                verifyBatchAfterReopen(reopened)
            } finally {
                reopened.close()
            }
        } finally {
            check(files.removeItemAtPath(directory, null))
        }
    }

    private fun withDatabase(block: suspend (AppDatabase) -> Unit) = runTest {
        val directory = NSTemporaryDirectory().trimEnd('/') + "/command-batch-${Uuid.random()}"
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val database = RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$directory/batch.db"))
        try {
            block(database)
        } finally {
            database.close()
            check(files.removeItemAtPath(directory, null))
        }
    }
}
