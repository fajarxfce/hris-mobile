@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserReplicaStagingTest {
    @Test
    fun workingReadsIncludeTombstonesAndHonorPublicationAndOwner() = runTest {
        withStagingStore { verifyWorkingReplicaDocuments(it) }
    }

    @Test
    fun discardRetainsVisibleSnapshotAndAllowsImmediateWrites() = runTest {
        withStagingStore { verifyReplicaStagingDiscard(it) }
    }

    @Test
    fun competingContinuationAndPublicationHaveOneWinner() = runTest {
        withStagingStore { verifyReplicaStagingRaces(it) }
    }

    @Test
    fun boundedAndCancelledDiscardCannotPartiallyRemoveData() = runTest {
        withStagingStore { verifyReplicaStagingBoundsAndCancellation(it) }
    }

    @Test
    fun restartAndReauthenticationRetainDiscardOwnership() = runTest {
        withStagingPath { path ->
            val original = openStagingStore(path)
            try {
                prepareReplicaStagingRestart(original)
            } finally {
                original.close()
            }
            val reopened = openStagingStore(path)
            try {
                verifyReplicaStagingAfterRestart(reopened)
            } finally {
                reopened.close()
            }
        }
    }
}

private suspend fun withStagingStore(block: suspend (AppDatabase) -> Unit) {
    withStagingPath { path ->
        val database = openStagingStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openStagingStore(path: String) = createAppDatabase(path)

private suspend fun withStagingPath(block: suspend (String) -> Unit) {
    val namespace = "replica-staging-" + kotlin.random.Random.nextInt()
    try {
        block(namespace)
    } finally {
        deleteStagingDatabase("$namespace.database").await<JsAny?>()
    }
}

@JsFun(
    """name => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Staging test database still open.'));
})"""
)
private external fun deleteStagingDatabase(name: String): Promise<JsAny?>
