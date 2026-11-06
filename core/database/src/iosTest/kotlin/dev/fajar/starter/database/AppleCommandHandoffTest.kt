@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleCommandHandoffTest {
    @Test fun replayAndArchives() = withDatabase { verifyCommandHandoffPreservesBoth(it) }

    @Test fun conflictsRollBack() = withDatabase { verifyCommandHandoffConflictsAndRollback(it) }

    @Test fun ownershipAndBounds() = withDatabase { verifyCommandHandoffOwnershipAndBounds(it) }

    @Test fun capacity() = withDatabase { verifyCommandHandoffAtCapacity(it) }

    @Test fun competingHandoffsAndStateUpdate() = withDatabase { verifyCommandHandoffRaces(it) }

    @Test fun concurrentSessionReplacement() = withDatabase { verifyCommandHandoffSessionRace(it) }

    @Test
    fun observationNeverExposesAHalfTransition() = withDatabase {
        verifyCommandHandoffObservation(it)
    }

    @Test fun cancellation() = withDatabase { verifyCommandHandoffCancellation(it) }

    @Test
    fun replaySurvivesReopenAndSessionReplacement() = runTest {
        val directory =
            NSTemporaryDirectory().trimEnd('/') + "/command-handoff-reopen-${Uuid.random()}"
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        fun open() = RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$directory/handoff.db"))
        try {
            val first = open()
            try {
                verifyCommandHandoffPreservesBoth(first)
            } finally {
                first.close()
            }
            val reopened = open()
            try {
                verifyCommandHandoffAfterReopen(reopened)
            } finally {
                reopened.close()
            }
        } finally {
            check(files.removeItemAtPath(directory, null))
        }
    }

    private fun withDatabase(block: suspend (AppDatabase) -> Unit) = runTest {
        val directory = NSTemporaryDirectory().trimEnd('/') + "/command-handoff-${Uuid.random()}"
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val database =
            RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$directory/handoff.db"))
        try {
            block(database)
        } finally {
            database.close()
            check(files.removeItemAtPath(directory, null))
        }
    }
}
