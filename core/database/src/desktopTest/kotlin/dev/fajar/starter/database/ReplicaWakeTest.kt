package dev.fajar.starter.database

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ReplicaWakeTest {
    @Test
    fun replacementAndRemovalCommitTheWakeWithData() = runTest {
        withReplicaWakeStore { verifyReplicaWakeReplacement(it) }
    }

    @Test
    fun stagingAndPublicationCommitTheirOwnWake() = runTest {
        withReplicaWakeStore { verifyReplicaWakeStagedCommit(it) }
    }

    @Test
    fun currentPendingAndAcknowledgedGenerationsCannotBeReused() = runTest {
        withReplicaWakeStore { verifyReplicaWakeGenerationReuse(it) }
    }

    @Test
    fun capacityConflictsCannotPartiallyMutateReplica() = runTest {
        withReplicaWakeStore { verifyReplicaWakeCapacity(it) }
    }

    @Test
    fun staleAccountAndCheckpointCannotRetainWake() = runTest {
        withReplicaWakeStore { verifyReplicaWakeOwnershipAndCheckpoint(it) }
    }

    @Test
    fun databaseConstraintFailureRollsBackDataAndWake() = runTest {
        withReplicaWakeStore { verifyReplicaWakeTransactionRollback(it) }
    }

    @Test
    fun oldConsumerCannotAcknowledgeConcurrentProducer() = runTest {
        withReplicaWakeStore { verifyReplicaWakeAcknowledgmentRace(it) }
    }

    @Test
    fun concurrentNewKeysShareOneCapacityLimit() = runTest {
        withReplicaWakeStore { verifyReplicaWakeConcurrentCapacity(it) }
    }

    @Test
    fun lostResponseReadbackDoesNotResurrectAcknowledgedWork() = runTest {
        withReplicaWakeStore { verifyReplicaWakeLostResponse(it) }
    }

    @Test
    fun boundsCancellationAndNoWakeCallsPreserveAtomicity() = runTest {
        withReplicaWakeStore { verifyReplicaWakeBoundsAndCancellation(it) }
    }

    @Test
    fun replicaAndPendingOrAcknowledgedWakeSurviveReopen() = runTest {
        withReplicaWakePath { path ->
            val original = openReplicaWakeStore(path)
            try {
                prepareReplicaWakeRestart(original)
            } finally {
                original.close()
            }
            val pending = openReplicaWakeStore(path)
            try {
                verifyReplicaWakeRestart(pending, acknowledged = false)
            } finally {
                pending.close()
            }
            val acknowledged = openReplicaWakeStore(path)
            try {
                verifyReplicaWakeRestart(acknowledged, acknowledged = true)
            } finally {
                acknowledged.close()
            }
        }
    }
}

private suspend fun withReplicaWakeStore(block: suspend (AppDatabase) -> Unit) {
    withReplicaWakePath { path ->
        val database = openReplicaWakeStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openReplicaWakeStore(path: java.io.File) = createAppDatabase(path)

private suspend fun withReplicaWakePath(block: suspend (java.io.File) -> Unit) {
    val directory = Files.createTempDirectory("replica-wake").toFile()
    try {
        block(directory)
    } finally {
        check(directory.deleteRecursively())
    }
}
