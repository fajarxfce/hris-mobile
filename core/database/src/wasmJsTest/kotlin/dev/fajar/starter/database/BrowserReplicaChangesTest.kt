@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserReplicaChangesTest {
    @Test
    fun initialRecordsPageByLocalPosition() = runTest {
        withReplicaChangesStore { verifyReplicaChangePaging(it) }
    }

    @Test
    fun callerVersionIsIndependentFromLocalChangeRevision() = runTest {
        withReplicaChangesStore { verifyReplicaChangeLocalRevision(it) }
    }

    @Test
    fun tombstonesAndIgnoredVersionsKeepTheirMeaning() = runTest {
        withReplicaChangesStore { verifyReplicaChangeTombstones(it) }
    }

    @Test
    fun writesDuringAFrozenWindowBelongToTheNextInterval() = runTest {
        withReplicaChangesStore { verifyReplicaChangeWindowDuringWrites(it) }
    }

    @Test
    fun stagingCannotReplaceThePublishedGeneration() = runTest {
        withReplicaChangesStore { verifyReplicaChangePublishedGeneration(it) }
    }

    @Test
    fun accountReplacementClosesOldScopeWithoutDeletingOwnedFacts() = runTest {
        withReplicaChangesStore { verifyReplicaChangeOwnership(it) }
    }

    @Test
    fun invalidWindowsAndMissingSourcesDoNotCreateRecords() = runTest {
        withReplicaChangesStore { verifyReplicaChangeBoundsAndAbsence(it) }
    }

    @Test
    fun maximumPageUsesOneBoundedLookahead() = runTest {
        withReplicaChangesStore { verifyReplicaChangeMaximumPage(it) }
    }

    @Test
    fun aSecondReplicaConflictRollsBackChangePositions() = runTest {
        withReplicaChangesStore { verifyReplicaChangeAtomicRollback(it) }
    }

    @Test
    fun concurrentWritersPublishOnlyOneChangePosition() = runTest {
        withReplicaChangesStore { verifyReplicaChangeCompetingWriters(it) }
    }

    @Test
    fun cancellationRetainsAllOrNoneOfTheChange() = runTest {
        withReplicaChangesStore { verifyReplicaChangeCancellation(it) }
    }

    @Test
    fun localChangePositionsSurviveDatabaseReopening() = runTest {
        withReplicaChangesPath { path ->
            val first = openReplicaChangesStore(path)
            try {
                prepareReplicaChangeRestart(first)
            } finally {
                first.close()
            }
            val second = openReplicaChangesStore(path)
            try {
                verifyReplicaChangeRestart(second)
            } finally {
                second.close()
            }
        }
    }
}

private suspend fun withReplicaChangesStore(block: suspend (AppDatabase) -> Unit) {
    withReplicaChangesPath { path ->
        val database = openReplicaChangesStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openReplicaChangesStore(path: String) = createAppDatabase(path)

private suspend fun withReplicaChangesPath(block: suspend (String) -> Unit) {
    val namespace = "replica-changes-" + kotlin.random.Random.nextInt()
    try {
        block(namespace)
    } finally {
        deleteReplicaChangesDatabase("$namespace.database").await<JsAny?>()
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
private external fun deleteReplicaChangesDatabase(name: String): Promise<JsAny?>
