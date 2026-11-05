@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserCommandBatchTest {
    @Test fun replayAndArchives() = withDatabase { verifyBatchReplayAndArchives(it) }

    @Test fun conflictsRollBack() = withDatabase { verifyBatchConflictRollsBack(it) }

    @Test fun ownershipAndBounds() = withDatabase { verifyBatchOwnershipAndBounds(it) }

    @Test fun capacity() = withDatabase { verifyBatchCapacity(it) }

    @Test fun racesAndCancellation() = withDatabase { verifyBatchRacesAndCancellation(it) }

    @Test
    fun replaySurvivesReopenAndSessionReplacement() = runTest {
        val namespace = "command-batch-reopen-" + kotlin.random.Random.nextInt()
        try {
            val first = createAppDatabase(namespace)
            try {
                verifyBatchReplayAndArchives(first)
            } finally {
                first.close()
            }
            val reopened = createAppDatabase(namespace)
            try {
                verifyBatchAfterReopen(reopened)
            } finally {
                reopened.close()
            }
        } finally {
            deleteBatchDatabase(namespace + ".database").await<JsAny?>()
        }
    }

    private fun withDatabase(block: suspend (AppDatabase) -> Unit) = runTest {
        val namespace = "command-batch-" + kotlin.random.Random.nextInt()
        val database = createAppDatabase(namespace)
        try {
            block(database)
        } finally {
            database.close()
            deleteBatchDatabase(namespace + ".database").await<JsAny?>()
        }
    }
}

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null); request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Database still owned.'));
})"""
)
private external fun deleteBatchDatabase(name: String): Promise<JsAny?>
