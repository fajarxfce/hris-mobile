@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserCommandInsertMutationsTest {
    @Test
    fun commandProjectionAndWakeCommitAndNotifyTogether() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationCommit(it) }
    }

    @Test
    fun sourceOrIndexChangesRollBackTheInsertedCommand() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationConditions(it) }
    }

    @Test
    fun wakeConflictsLeaveNoCommandOrProjection() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationWakeConflicts(it) }
    }

    @Test
    fun activeAndArchivedReplayNeverRestoresOldProjectionOrWake() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationReplay(it) }
    }

    @Test
    fun commandIdentityAndResourceConflictsPreserveProjection() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationCommandConflicts(it) }
    }

    @Test
    fun invalidInputsAreRejectedEvenForRetainedCommands() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationBounds(it) }
    }

    @Test
    fun accountAndGlobalCapacityDoNotPublishOrRenewWork() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationCapacity(it) }
    }

    @Test
    fun projectionAndCommandConstraintsRollBackEveryStore() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationConstraints(it) }
    }

    @Test
    fun replacementAccountCannotWriteAndRenewedOwnerCanContinue() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationOwnership(it) }
    }

    @Test
    fun competingEditsPublishOnlyTheWinningCommandAndProjection() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationConcurrentWriters(it) }
    }

    @Test
    fun concurrentExactReplayPublishesOnlyOnce() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationConcurrentReplay(it) }
    }

    @Test
    fun callerCollectionsAreDetachedBeforeSuspension() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationFrozenInput(it) }
    }

    @Test
    fun cancelledWriterLeavesAllOrNoneCommitted() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationCancellation(it) }
    }

    @Test
    fun lostReplyIsRecoveredWithoutRevivingAcknowledgedWork() = runTest {
        withCommandInsertMutationsStore { verifyInsertMutationLostResponse(it) }
    }

    @Test
    fun commandProjectionAndWakeSurviveReopening() = runTest {
        withCommandInsertMutationsPath { path ->
            val first = openCommandInsertMutationsStore(path)
            try {
                prepareInsertMutationRestart(first)
            } finally {
                first.close()
            }
            val second = openCommandInsertMutationsStore(path)
            try {
                verifyInsertMutationRestart(second)
            } finally {
                second.close()
            }
        }
    }
}

private suspend fun withCommandInsertMutationsStore(block: suspend (AppDatabase) -> Unit) {
    withCommandInsertMutationsPath { path ->
        val database = openCommandInsertMutationsStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openCommandInsertMutationsStore(path: String) = createAppDatabase(path)

private suspend fun withCommandInsertMutationsPath(block: suspend (String) -> Unit) {
    val namespace = "command-insert-mutation-" + kotlin.random.Random.nextInt()
    try {
        block(namespace)
    } finally {
        deleteCommandInsertMutationsDatabase("$namespace.database").await<JsAny?>()
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
private external fun deleteCommandInsertMutationsDatabase(name: String): Promise<JsAny?>
