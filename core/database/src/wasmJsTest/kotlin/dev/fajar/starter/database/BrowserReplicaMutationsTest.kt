@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserReplicaMutationsTest {
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

private fun openReplicaMutationsStore(path: String) = createAppDatabase(path)

private suspend fun withReplicaMutationsPath(block: suspend (String) -> Unit) {
    val namespace = "replica-pair-" + kotlin.random.Random.nextInt()
    try {
        block(namespace)
    } finally {
        deleteReplicaMutationsDatabase("$namespace.database").await<JsAny?>()
    }
}

@JsFun(
    """name => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Replica pair test database still open.'));
})"""
)
private external fun deleteReplicaMutationsDatabase(name: String): Promise<JsAny?>
