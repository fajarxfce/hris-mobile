@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserReplicaWakeTest {
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

private fun openReplicaWakeStore(path: String) = createAppDatabase(path)

private suspend fun withReplicaWakePath(block: suspend (String) -> Unit) {
    val namespace = "replica-wake-" + kotlin.random.Random.nextInt()
    try {
        block(namespace)
    } finally {
        deleteReplicaWakeDatabase("$namespace.database").await<JsAny?>()
    }
}

@JsFun(
    """name => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Replica wake test database still open.'));
})"""
)
private external fun deleteReplicaWakeDatabase(name: String): Promise<JsAny?>
