@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleReplicaWakeTest {
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

private fun openReplicaWakeStore(path: String): AppDatabase =
    RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$path/replica-wake.db"))

private suspend fun withReplicaWakePath(block: suspend (String) -> Unit) {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/replica-wake-${Uuid.random()}"
    val files = NSFileManager.defaultManager
    check(files.createDirectoryAtPath(directory, true, null, null))
    try {
        block(directory)
    } finally {
        check(files.removeItemAtPath(directory, null))
    }
}
