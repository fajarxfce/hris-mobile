@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserCommandWakeTest {
    @Test
    fun newCommandsAndMixedArchivedBatchCommitOneWake() = runTest {
        withCommandWakeStore { verifyCommandWakeInsertion(it) }
    }

    @Test
    fun activeAndArchivedReplayNeverCreatesWork() = runTest {
        withCommandWakeStore { verifyCommandWakeReplay(it) }
    }

    @Test
    fun pendingAndAcknowledgedGenerationCannotBeReused() = runTest {
        withCommandWakeStore { verifyCommandWakeGeneration(it) }
    }

    @Test
    fun wakeAndCommandCapacityRejectWithoutMutation() = runTest {
        withCommandWakeStore { verifyCommandWakeCapacity(it) }
    }

    @Test
    fun scopeResourceAndIdentityConflictsPreserveBothStores() = runTest {
        withCommandWakeStore { verifyCommandWakeOwnershipAndIdentity(it) }
    }

    @Test
    fun lateDatabaseConstraintRollsBackCommandsAndWake() = runTest {
        withCommandWakeStore { verifyCommandWakeConstraintRollback(it) }
    }

    @Test
    fun oldConsumerCannotAcknowledgeNewCommands() = runTest {
        withCommandWakeStore { verifyCommandWakeAcknowledgmentRace(it) }
    }

    @Test
    fun concurrentNewKeysShareOneCapacityLimit() = runTest {
        withCommandWakeStore { verifyCommandWakeConcurrentCapacity(it) }
    }

    @Test
    fun lostResponseRequiresReadbackAndNeverResurrectsWake() = runTest {
        withCommandWakeStore { verifyCommandWakeLostResponse(it) }
    }

    @Test
    fun invalidBoundsLeaveBothStoresUnchanged() = runTest {
        withCommandWakeStore { verifyCommandWakeBounds(it) }
    }

    @Test
    fun cancellationLeavesNeitherOrBothStoresCommitted() = runTest {
        withCommandWakeStore { verifyCommandWakeCancellation(it) }
    }

    @Test
    fun existingInsertionApisLeaveWakeUntouched() = runTest {
        withCommandWakeStore { verifyCommandWakeCompatibility(it) }
    }

    @Test
    fun commandsArchivesAndPendingOrAcknowledgedWakeSurviveReopen() = runTest {
        withCommandWakePath { path ->
            val original = openCommandWakeStore(path)
            try {
                prepareCommandWakeRestart(original)
            } finally {
                original.close()
            }
            val pending = openCommandWakeStore(path)
            try {
                verifyCommandWakeRestart(pending, acknowledged = false)
            } finally {
                pending.close()
            }
            val acknowledged = openCommandWakeStore(path)
            try {
                verifyCommandWakeRestart(acknowledged, acknowledged = true)
            } finally {
                acknowledged.close()
            }
        }
    }
}

private suspend fun withCommandWakeStore(block: suspend (AppDatabase) -> Unit) {
    withCommandWakePath { path ->
        val database = openCommandWakeStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openCommandWakeStore(path: String) = createAppDatabase(path)

private suspend fun withCommandWakePath(block: suspend (String) -> Unit) {
    val namespace = "command-wake-" + kotlin.random.Random.nextInt()
    try {
        block(namespace)
    } finally {
        deleteCommandWakeDatabase("$namespace.database").await<JsAny?>()
    }
}

@JsFun(
    """name => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Command wake test database still open.'));
})"""
)
private external fun deleteCommandWakeDatabase(name: String): Promise<JsAny?>
