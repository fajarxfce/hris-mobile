package dev.fajar.starter.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CommandInsertMutationsTest {
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

    @Test
    fun sqliteInsertFailureLeavesProjectionAndWakeUntouched() = runTest {
        withCommandInsertMutationsPath { path ->
            val database = openCommandInsertMutationsStore(path)
            try {
                val f = InsertMutationFixture(database)
                f.seed()
                BundledSQLiteDriver().open(path.resolve("starter.db").absolutePath).use { connection
                    ->
                    connection
                        .prepare(
                            "CREATE TRIGGER fail_command AFTER INSERT ON durable_commands BEGIN SELECT RAISE(ABORT, 'fixture'); END"
                        )
                        .use { it.step() }
                }
                assertFails { f.insert() }
                f.unchanged()
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun sqliteCheckpointFailureRollsBackEarlierCommandProjectionAndWake() = runTest {
        withCommandInsertMutationsPath { path ->
            val database = openCommandInsertMutationsStore(path)
            try {
                val f = InsertMutationFixture(database)
                f.seed()
                BundledSQLiteDriver().open(path.resolve("starter.db").absolutePath).use { connection
                    ->
                    connection
                        .prepare(
                            "CREATE TRIGGER fail_projection BEFORE UPDATE ON replica_generations WHEN NEW.partition = 'inbox/projection' BEGIN SELECT RAISE(ABORT, 'fixture'); END"
                        )
                        .use { it.step() }
                }
                assertFails { f.insert() }
                f.unchanged()
            } finally {
                database.close()
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

private fun openCommandInsertMutationsStore(path: java.io.File) = createAppDatabase(path)

private suspend fun withCommandInsertMutationsPath(block: suspend (java.io.File) -> Unit) {
    val directory = Files.createTempDirectory("command-insert-mutation").toFile()
    try {
        block(directory)
    } finally {
        check(directory.deleteRecursively())
    }
}
