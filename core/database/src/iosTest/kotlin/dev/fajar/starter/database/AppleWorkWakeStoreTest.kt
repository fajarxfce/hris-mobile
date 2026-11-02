@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleWorkWakeStoreTest {
    @Test
    fun collapseAndAcknowledgmentKeepTheNewestGeneration() = runTest {
        withDatabase { verifyWorkWakeGenerations(it.workWakes) }
    }

    @Test
    fun concurrentNewKeysCannotExceedCapacityButExistingKeysCanAdvance() = runTest {
        withDatabase { verifyWorkWakeCapacity(it.workWakes) }
    }

    @Test
    fun anOldCompletionCannotEraseAConcurrentWake() = runTest {
        withDatabase { verifyWorkWakeAcknowledgmentRace(it.workWakes) }
    }

    @Test
    fun schedulingMetadataSurvivesAccountReplacementWithoutCarryingAccountIdentity() = runTest {
        withDatabase { verifyWorkWakeGlobalOwnership(it) }
    }

    @Test
    fun cancellationAndInvalidBoundsCannotCreateAWake() = runTest {
        withDatabase { verifyWorkWakeCancellationAndBounds(it.workWakes) }
    }

    @Test
    fun pendingAndAcknowledgedGenerationsSurviveClosingAndReopeningTheDatabase() = runTest {
        withWakeDirectory { directory ->
            var database = openWakeDatabase(directory)
            val pending = WorkWakeRecord("chat.recovery", "original", true)
            try {
                assertEquals(
                    pending,
                    database.workWakes.request(pending.key, pending.generation, 1),
                )
                database.close()
                database = openWakeDatabase(directory)
                assertEquals(pending, database.workWakes.get(pending.key))
                assertTrue(database.workWakes.acknowledge(pending.key, pending.generation))
                database.close()
                database = openWakeDatabase(directory)
                assertEquals(pending.copy(pending = false), database.workWakes.get(pending.key))
                assertEquals(
                    pending.copy(pending = false),
                    database.workWakes.request(pending.key, pending.generation, 1),
                )
            } finally {
                database.close()
            }
        }
    }

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        withWakeDirectory { directory ->
            val database = openWakeDatabase(directory)
            try {
                block(database)
            } finally {
                database.close()
            }
        }
    }
}

private fun openWakeDatabase(path: String): AppDatabase =
    RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$path/wake.db"))

private suspend fun withWakeDirectory(block: suspend (String) -> Unit) {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/work-wake-test-${Uuid.random()}"
    val files = NSFileManager.defaultManager
    check(files.createDirectoryAtPath(directory, true, null, null))
    try {
        block(directory)
    } finally {
        check(files.removeItemAtPath(directory, null))
    }
}
