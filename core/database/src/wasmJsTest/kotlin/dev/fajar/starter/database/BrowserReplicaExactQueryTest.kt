@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserReplicaExactQueryTest {
    @Test
    fun exactIdentityAndIndexesShareOneCheckpointThroughDeletionAndRevival() = runTest {
        withReplicaExactQueriesStore { verifyReplicaExactSelections(it) }
    }

    @Test
    fun publicationOwnershipAndDisposalRetainCoherentExactRecords() = runTest {
        withReplicaExactQueriesStore { verifyReplicaExactPublication(it) }
    }

    @Test
    fun exactSelectionRejectsAmbiguityAndCapturesCallerInput() = runTest {
        withReplicaExactQueriesStore { verifyReplicaExactBounds(it) }
    }

    @Test
    fun exactRecordsSurviveRestartWhileStagingStaysHidden() = runTest {
        withReplicaExactQueriesPath { path ->
            val first = openReplicaExactQueriesStore(path)
            try {
                prepareReplicaExactRestart(first)
            } finally {
                first.close()
            }
            val second = openReplicaExactQueriesStore(path)
            try {
                verifyReplicaExactRestart(second)
            } finally {
                second.close()
            }
        }
    }
}

private suspend fun withReplicaExactQueriesStore(block: suspend (AppDatabase) -> Unit) {
    withReplicaExactQueriesPath { path ->
        val database = openReplicaExactQueriesStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openReplicaExactQueriesStore(path: String) = createAppDatabase(path)

private suspend fun withReplicaExactQueriesPath(block: suspend (String) -> Unit) {
    val namespace = "replica-exact-query-" + kotlin.random.Random.nextInt()
    try {
        block(namespace)
    } finally {
        deleteReplicaExactQueriesDatabase("$namespace.database").await<JsAny?>()
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
private external fun deleteReplicaExactQueriesDatabase(name: String): Promise<JsAny?>
