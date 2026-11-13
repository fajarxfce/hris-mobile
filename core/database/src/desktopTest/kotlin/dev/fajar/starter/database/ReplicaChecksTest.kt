package dev.fajar.starter.database

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ReplicaChecksTest {
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

private fun openReplicaChecksStore(path: java.io.File) = createAppDatabase(path)

private suspend fun withReplicaChecksPath(block: suspend (java.io.File) -> Unit) {
    val directory = Files.createTempDirectory("replica-check").toFile()
    try {
        block(directory)
    } finally {
        check(directory.deleteRecursively())
    }
}
