@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleReplicaMutationsTest {
    @Test
    fun replacementAndRemovalPublishBothPartitions() = runTest {
        withReplicaMutationsStore { verifyReplicaPairReplacementAndRemoval(it) }
    }

    @Test
    fun mixedStagingAndDeltaKeepPublishedVisibility() = runTest {
        withReplicaMutationsStore { verifyReplicaPairMixedStaging(it) }
    }

    @Test
    fun eitherStaleCheckpointPreservesBothPartitionsAndWake() = runTest {
        withReplicaMutationsStore { verifyReplicaPairStaleConditions(it) }
    }

    @Test
    fun reusedGenerationRejectsTheWholeMutation() = runTest {
        withReplicaMutationsStore { verifyReplicaPairGenerationReuse(it) }
    }

    @Test
    fun secondDatabaseConstraintFailureRollsBackFirstMutationAndWake() = runTest {
        withReplicaMutationsStore { verifyReplicaPairConstraintRollback(it) }
    }

    @Test
    fun tombstonesVersionsAndTagsRetainTheirExistingSemantics() = runTest {
        withReplicaMutationsStore { verifyReplicaPairVersionsAndTags(it) }
    }

    @Test
    fun replacementAccountCannotWriteAndRenewedOwnerKeepsOriginals() = runTest {
        withReplicaMutationsStore { verifyReplicaPairOwnership(it) }
    }

    @Test
    fun invalidBatchesAreRejectedBeforeAnyMutation() = runTest {
        withReplicaMutationsStore { verifyReplicaPairBounds(it) }
    }

    @Test
    fun wakeCapacityOrReusedGenerationPreservesBothPartitions() = runTest {
        withReplicaMutationsStore { verifyReplicaPairWakeConflict(it) }
    }

    @Test
    fun competingWritersCommitOnlyOneConsistentPair() = runTest {
        withReplicaMutationsStore { verifyReplicaPairConcurrentWriters(it) }
    }

    @Test
    fun callerCollectionMutationCannotChangeTheCapturedWrite() = runTest {
        withReplicaMutationsStore { verifyReplicaPairFrozenInput(it) }
    }

    @Test
    fun cancelledWritesLeaveEitherAllOrNoneCommitted() = runTest {
        withReplicaMutationsStore { verifyReplicaPairCancellation(it) }
    }

    @Test
    fun lostResponseReadbackCannotResurrectAnAcknowledgedWake() = runTest {
        withReplicaMutationsStore { verifyReplicaPairLostResponse(it) }
    }

    @Test
    fun bothPartitionsAndWakeSurviveRestart() = runTest {
        withReplicaMutationsPath { path ->
            val first = openReplicaMutationsStore(path)
            try {
                prepareReplicaPairRestart(first)
            } finally {
                first.close()
            }
            val second = openReplicaMutationsStore(path)
            try {
                verifyReplicaPairRestart(second)
            } finally {
                second.close()
            }
        }
    }
}

private suspend fun withReplicaMutationsStore(block: suspend (AppDatabase) -> Unit) {
    withReplicaMutationsPath { path ->
        val database = openReplicaMutationsStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openReplicaMutationsStore(path: String): AppDatabase =
    RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$path/replica-pair.db"))

private suspend fun withReplicaMutationsPath(block: suspend (String) -> Unit) {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/replica-pair-${Uuid.random()}"
    val files = NSFileManager.defaultManager
    check(files.createDirectoryAtPath(directory, true, null, null))
    try {
        block(directory)
    } finally {
        check(files.removeItemAtPath(directory, null))
    }
}
