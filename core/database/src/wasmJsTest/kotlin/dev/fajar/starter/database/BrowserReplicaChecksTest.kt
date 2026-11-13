@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserReplicaChecksTest {
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

private fun openReplicaChecksStore(path: String) = createAppDatabase(path)

private suspend fun withReplicaChecksPath(block: suspend (String) -> Unit) {
    val namespace = "replica-check-" + kotlin.random.Random.nextInt()
    try {
        block(namespace)
    } finally {
        deleteReplicaChecksDatabase("$namespace.database").await<JsAny?>()
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
private external fun deleteReplicaChecksDatabase(name: String): Promise<JsAny?>
