@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleCommandArchiveMutationsTest {
    @Test
    fun mixedReplicaMutationsAndArchiveCommitTogether() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationCommit(it) }
    }

    @Test
    fun exactArchiveReplayDoesNotRewriteLaterReplicaProgress() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationReplay(it) }
    }

    @Test
    fun anyChangedCommandFieldLeavesEveryStoreUntouched() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationCommandConflicts(it) }
    }

    @Test
    fun eitherReplicaConflictOrGenerationReusePreservesCommand() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationReplicaConflicts(it) }
    }

    @Test
    fun secondReplicaConstraintFailureRollsBackEveryStore() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationConstraintRollback(it) }
    }

    @Test
    fun replacementAccountIsDeniedAndRenewedOwnerCanComplete() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationOwnership(it) }
    }

    @Test
    fun invalidOrForeignBatchesAreRejectedIncludingOnReplay() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationBounds(it) }
    }

    @Test
    fun commandUpdateAndArchivalHaveOneConsistentWinner() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationCompetingWriters(it) }
    }

    @Test
    fun independentProgressAndArchivalHaveOneConsistentWinner() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationCompetingProgress(it) }
    }

    @Test
    fun callerCollectionsAreDetachedBeforeSuspending() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationFrozenInput(it) }
    }

    @Test
    fun cancelledWritesLeaveAllOrNoneCommitted() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationCancellation(it) }
    }

    @Test
    fun lostResponseCanBeConfirmedWithoutRepeatingDemand() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationLostResponse(it) }
    }

    @Test
    fun emptyActiveInventoryStillPublishesDurableReplicaDemand() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationEmptyInventoryDemand(it) }
    }

    @Test
    fun archiveAndReplicaDemandSurviveReopening() = runTest {
        withArchiveMutationsPath { path ->
            val first = openArchiveMutationsStore(path)
            try {
                prepareArchiveMutationRestart(first)
            } finally {
                first.close()
            }
            val second = openArchiveMutationsStore(path)
            try {
                verifyArchiveMutationRestart(second)
            } finally {
                second.close()
            }
        }
    }

    @Test
    fun failureDeletingArchivedCommandRollsBackEarlierReplicaWrites() = runTest {
        withArchiveMutationsPath { path ->
            val database = openArchiveMutationsStore(path)
            try {
                val fixture = ArchiveMutationFixture(database)
                fixture.seed()
                BundledSQLiteDriver().open("$path/archive-mutations.db").use { connection ->
                    connection
                        .prepare(
                            "CREATE TRIGGER reject_archive_delete BEFORE DELETE ON durable_commands BEGIN SELECT RAISE(ABORT, 'fixture interruption'); END"
                        )
                        .use { it.step() }
                }
                try {
                    assertFails {
                        database.commands.archiveAndMutate(
                            fixture.scope,
                            fixture.command,
                            fixture.evidence,
                            fixture.mutations(),
                        )
                    }
                } finally {
                    BundledSQLiteDriver().open("$path/archive-mutations.db").use { connection ->
                        connection.prepare("DROP TRIGGER reject_archive_delete").use { it.step() }
                    }
                }
                fixture.unchanged()
                assertTrue(
                    database.commands.archiveAndMutate(
                        fixture.scope,
                        fixture.command,
                        fixture.evidence,
                        fixture.mutations(),
                    )
                )
                fixture.completed()
            } finally {
                database.close()
            }
        }
    }
}

private suspend fun withArchiveMutationsStore(block: suspend (AppDatabase) -> Unit) {
    withArchiveMutationsPath { path ->
        val database = openArchiveMutationsStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openArchiveMutationsStore(path: String): AppDatabase =
    RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$path/archive-mutations.db"))

private suspend fun withArchiveMutationsPath(block: suspend (String) -> Unit) {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/archive-mutations-${Uuid.random()}"
    val files = NSFileManager.defaultManager
    check(files.createDirectoryAtPath(directory, true, null, null))
    try {
        block(directory)
    } finally {
        check(files.removeItemAtPath(directory, null))
    }
}
