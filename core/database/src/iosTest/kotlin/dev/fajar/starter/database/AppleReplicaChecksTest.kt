@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleReplicaChecksTest {
    @Test
    fun exactMatchPublishesProjectionAndWakeWithoutChangingSource() = runTest {
        withReplicaChecksStore { verifyReplicaCheckPublication(it) }
    }

    @Test
    fun confirmedAbsenceDoesNotCreateSourceAndRejectsLaterCreation() = runTest {
        withReplicaChecksStore { verifyReplicaCheckAbsence(it) }
    }

    @Test
    fun allConditionsPrecedeWritesRegardlessOfMutationOrder() = runTest {
        withReplicaChecksStore { verifyReplicaCheckPreflightOrdering(it) }
    }

    @Test
    fun exactCheckpointIncludesPayloadAndStagingVisibility() = runTest {
        withReplicaChecksStore { verifyReplicaCheckExactStaging(it) }
    }

    @Test
    fun foreignScopeAndReplacementAccountCannotPublish() = runTest {
        withReplicaChecksStore { verifyReplicaCheckOwnership(it) }
    }

    @Test
    fun readOnlyDuplicateAndOversizedBatchesAreRejected() = runTest {
        withReplicaChecksStore { verifyReplicaCheckBounds(it) }
    }

    @Test
    fun wakeConflictsPreserveSourceAndProjection() = runTest {
        withReplicaChecksStore { verifyReplicaCheckWakeConflict(it) }
    }

    @Test
    fun constraintFailureRollsBackProjectionAndWake() = runTest {
        withReplicaChecksStore { verifyReplicaCheckRollback(it) }
    }

    @Test
    fun competingWritersPublishOnlyOneProjection() = runTest {
        withReplicaChecksStore { verifyReplicaCheckConcurrentWriters(it) }
    }

    @Test
    fun cancelledWritersRetainSourceAndAtomicProjectionWake() = runTest {
        withReplicaChecksStore { verifyReplicaCheckCancellation(it) }
    }

    @Test
    fun lostResponseReadbackCannotResurrectAcknowledgedWake() = runTest {
        withReplicaChecksStore { verifyReplicaCheckLostResponse(it) }
    }

    @Test
    fun archiveReplayDoesNotRecheckAnAlreadyCompletedSource() = runTest {
        withReplicaChecksStore { verifyReplicaCheckArchiveReplay(it) }
    }

    @Test
    fun checkedProjectionSurvivesRestartWithOriginalSource() = runTest {
        withReplicaChecksPath { path ->
            val first = openReplicaChecksStore(path)
            try {
                prepareReplicaCheckRestart(first)
            } finally {
                first.close()
            }
            val second = openReplicaChecksStore(path)
            try {
                verifyReplicaCheckRestart(second)
            } finally {
                second.close()
            }
        }
    }
}

private suspend fun withReplicaChecksStore(block: suspend (AppDatabase) -> Unit) {
    withReplicaChecksPath { path ->
        val database = openReplicaChecksStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openReplicaChecksStore(path: String): AppDatabase =
    RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$path/replica-check.db"))

private suspend fun withReplicaChecksPath(block: suspend (String) -> Unit) {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/replica-check-${Uuid.random()}"
    val files = NSFileManager.defaultManager
    check(files.createDirectoryAtPath(directory, true, null, null))
    try {
        block(directory)
    } finally {
        check(files.removeItemAtPath(directory, null))
    }
}
