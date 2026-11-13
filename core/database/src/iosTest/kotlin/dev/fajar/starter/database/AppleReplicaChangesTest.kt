@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleReplicaChangesTest {
    @Test
    fun initialRecordsPageByLocalPosition() = runTest {
        withReplicaChangesStore { verifyReplicaChangePaging(it) }
    }

    @Test
    fun callerVersionIsIndependentFromLocalChangeRevision() = runTest {
        withReplicaChangesStore { verifyReplicaChangeLocalRevision(it) }
    }

    @Test
    fun tombstonesAndIgnoredVersionsKeepTheirMeaning() = runTest {
        withReplicaChangesStore { verifyReplicaChangeTombstones(it) }
    }

    @Test
    fun writesDuringAFrozenWindowBelongToTheNextInterval() = runTest {
        withReplicaChangesStore { verifyReplicaChangeWindowDuringWrites(it) }
    }

    @Test
    fun stagingCannotReplaceThePublishedGeneration() = runTest {
        withReplicaChangesStore { verifyReplicaChangePublishedGeneration(it) }
    }

    @Test
    fun accountReplacementClosesOldScopeWithoutDeletingOwnedFacts() = runTest {
        withReplicaChangesStore { verifyReplicaChangeOwnership(it) }
    }

    @Test
    fun invalidWindowsAndMissingSourcesDoNotCreateRecords() = runTest {
        withReplicaChangesStore { verifyReplicaChangeBoundsAndAbsence(it) }
    }

    @Test
    fun maximumPageUsesOneBoundedLookahead() = runTest {
        withReplicaChangesStore { verifyReplicaChangeMaximumPage(it) }
    }

    @Test
    fun aSecondReplicaConflictRollsBackChangePositions() = runTest {
        withReplicaChangesStore { verifyReplicaChangeAtomicRollback(it) }
    }

    @Test
    fun concurrentWritersPublishOnlyOneChangePosition() = runTest {
        withReplicaChangesStore { verifyReplicaChangeCompetingWriters(it) }
    }

    @Test
    fun cancellationRetainsAllOrNoneOfTheChange() = runTest {
        withReplicaChangesStore { verifyReplicaChangeCancellation(it) }
    }

    @Test
    fun localChangePositionsSurviveDatabaseReopening() = runTest {
        withReplicaChangesPath { path ->
            val first = openReplicaChangesStore(path)
            try {
                prepareReplicaChangeRestart(first)
            } finally {
                first.close()
            }
            val second = openReplicaChangesStore(path)
            try {
                verifyReplicaChangeRestart(second)
            } finally {
                second.close()
            }
        }
    }
}

private suspend fun withReplicaChangesStore(block: suspend (AppDatabase) -> Unit) {
    withReplicaChangesPath { path ->
        val database = openReplicaChangesStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openReplicaChangesStore(path: String): AppDatabase =
    RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$path/replica-changes.db"))

private suspend fun withReplicaChangesPath(block: suspend (String) -> Unit) {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/replica-changes-${Uuid.random()}"
    val files = NSFileManager.defaultManager
    check(files.createDirectoryAtPath(directory, true, null, null))
    try {
        block(directory)
    } finally {
        check(files.removeItemAtPath(directory, null))
    }
}
