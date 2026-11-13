@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleCommandInsertMutationsTest {
    @Test
    fun commandProjectionAndWakeCommitAndNotifyTogether() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationCommit(it) }
    }

    @Test
    fun sourceOrIndexChangesRollBackTheInsertedCommand() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationConditions(it) }
    }

    @Test
    fun wakeConflictsLeaveNoCommandOrProjection() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationWakeConflicts(it) }
    }

    @Test
    fun activeAndArchivedReplayNeverRestoresOldProjectionOrWake() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationReplay(it) }
    }

    @Test
    fun commandIdentityAndResourceConflictsPreserveProjection() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationCommandConflicts(it) }
    }

    @Test
    fun invalidInputsAreRejectedEvenForRetainedCommands() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationBounds(it) }
    }

    @Test
    fun accountAndGlobalCapacityDoNotPublishOrRenewWork() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationCapacity(it) }
    }

    @Test
    fun projectionAndCommandConstraintsRollBackEveryStore() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationConstraints(it) }
    }

    @Test
    fun replacementAccountCannotWriteAndRenewedOwnerCanContinue() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationOwnership(it) }
    }

    @Test
    fun competingEditsPublishOnlyTheWinningCommandAndProjection() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationConcurrentWriters(it) }
    }

    @Test
    fun concurrentExactReplayPublishesOnlyOnce() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationConcurrentReplay(it) }
    }

    @Test
    fun callerCollectionsAreDetachedBeforeSuspension() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationFrozenInput(it) }
    }

    @Test
    fun cancelledWriterLeavesAllOrNoneCommitted() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationCancellation(it) }
    }

    @Test
    fun lostReplyIsRecoveredWithoutRevivingAcknowledgedWork() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationLostResponse(it) }
    }

    @Test
    fun commandProjectionAndWakeSurviveReopening() = runTest {
        withCommandInsertMutationsPath { path ->
            val first = openCommandInsertMutationsStore(path)
            try {
                prepareInsertMutationRestart(first)
            } finally {
                first.close()
            }
            val second = openCommandInsertMutationsStore(path)
            try {
                verifyInsertMutationRestart(second)
            } finally {
                second.close()
            }
        }
    }
}

private suspend fun withCommandInsertMutationsStore(block: suspend (AppDatabase) -> Unit) {
    withCommandInsertMutationsPath { path ->
        val database = openCommandInsertMutationsStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openCommandInsertMutationsStore(path: String): AppDatabase =
    RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$path/command-insert-mutation.db"))

private suspend fun withCommandInsertMutationsPath(block: suspend (String) -> Unit) {
    val directory =
        NSTemporaryDirectory().trimEnd('/') + "/command-insert-mutation-${Uuid.random()}"
    val files = NSFileManager.defaultManager
    check(files.createDirectoryAtPath(directory, true, null, null))
    try {
        block(directory)
    } finally {
        check(files.removeItemAtPath(directory, null))
    }
}
