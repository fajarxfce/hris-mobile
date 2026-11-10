@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleCommandWakeTest {
    @Test
    fun newCommandsAndMixedArchivedBatchCommitOneWake() = runTest {
        withCommandWakeStore { verifyCommandWakeInsertion(it) }
    }

    @Test
    fun activeAndArchivedReplayNeverCreatesWork() = runTest {
        withCommandWakeStore { verifyCommandWakeReplay(it) }
    }

    @Test
    fun pendingAndAcknowledgedGenerationCannotBeReused() = runTest {
        withCommandWakeStore { verifyCommandWakeGeneration(it) }
    }

    @Test
    fun wakeAndCommandCapacityRejectWithoutMutation() = runTest {
        withCommandWakeStore { verifyCommandWakeCapacity(it) }
    }

    @Test
    fun scopeResourceAndIdentityConflictsPreserveBothStores() = runTest {
        withCommandWakeStore { verifyCommandWakeOwnershipAndIdentity(it) }
    }

    @Test
    fun lateDatabaseConstraintRollsBackCommandsAndWake() = runTest {
        withCommandWakeStore { verifyCommandWakeConstraintRollback(it) }
    }

    @Test
    fun oldConsumerCannotAcknowledgeNewCommands() = runTest {
        withCommandWakeStore { verifyCommandWakeAcknowledgmentRace(it) }
    }

    @Test
    fun concurrentNewKeysShareOneCapacityLimit() = runTest {
        withCommandWakeStore { verifyCommandWakeConcurrentCapacity(it) }
    }

    @Test
    fun lostResponseRequiresReadbackAndNeverResurrectsWake() = runTest {
        withCommandWakeStore { verifyCommandWakeLostResponse(it) }
    }

    @Test
    fun invalidBoundsLeaveBothStoresUnchanged() = runTest {
        withCommandWakeStore { verifyCommandWakeBounds(it) }
    }

    @Test
    fun cancellationLeavesNeitherOrBothStoresCommitted() = runTest {
        withCommandWakeStore { verifyCommandWakeCancellation(it) }
    }

    @Test
    fun existingInsertionApisLeaveWakeUntouched() = runTest {
        withCommandWakeStore { verifyCommandWakeCompatibility(it) }
    }

    @Test
    fun commandsArchivesAndPendingOrAcknowledgedWakeSurviveReopen() = runTest {
        withCommandWakePath { path ->
            val original = openCommandWakeStore(path)
            try {
                prepareCommandWakeRestart(original)
            } finally {
                original.close()
            }
            val pending = openCommandWakeStore(path)
            try {
                verifyCommandWakeRestart(pending, acknowledged = false)
            } finally {
                pending.close()
            }
            val acknowledged = openCommandWakeStore(path)
            try {
                verifyCommandWakeRestart(acknowledged, acknowledged = true)
            } finally {
                acknowledged.close()
            }
        }
    }
}

private suspend fun withCommandWakeStore(block: suspend (AppDatabase) -> Unit) {
    withCommandWakePath { path ->
        val database = openCommandWakeStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openCommandWakeStore(path: String): AppDatabase =
    RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$path/command-wake.db"))

private suspend fun withCommandWakePath(block: suspend (String) -> Unit) {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/command-wake-${Uuid.random()}"
    val files = NSFileManager.defaultManager
    check(files.createDirectoryAtPath(directory, true, null, null))
    try {
        block(directory)
    } finally {
        check(files.removeItemAtPath(directory, null))
    }
}
