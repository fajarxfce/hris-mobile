package dev.fajar.starter.database

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CommandWakeTest {
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

private fun openCommandWakeStore(path: java.io.File) = createAppDatabase(path)

private suspend fun withCommandWakePath(block: suspend (java.io.File) -> Unit) {
    val directory = Files.createTempDirectory("command-wake").toFile()
    try {
        block(directory)
    } finally {
        check(directory.deleteRecursively())
    }
}
