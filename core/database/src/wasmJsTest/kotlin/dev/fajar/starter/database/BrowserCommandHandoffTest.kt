@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserCommandHandoffTest {
    @Test fun replayAndArchives() = withDatabase { verifyCommandHandoffPreservesBoth(it) }

    @Test fun conflictsRollBack() = withDatabase { verifyCommandHandoffConflictsAndRollback(it) }

    @Test fun ownershipAndBounds() = withDatabase { verifyCommandHandoffOwnershipAndBounds(it) }

    @Test fun capacity() = withDatabase { verifyCommandHandoffAtCapacity(it) }

    @Test fun competingHandoffsAndStateUpdate() = withDatabase { verifyCommandHandoffRaces(it) }

    @Test fun concurrentSessionReplacement() = withDatabase { verifyCommandHandoffSessionRace(it) }

    @Test
    fun observationNeverExposesAHalfTransition() = withDatabase {
        verifyCommandHandoffObservation(it)
    }

    @Test fun cancellation() = withDatabase { verifyCommandHandoffCancellation(it) }

    @Test
    fun replaySurvivesReopenAndSessionReplacement() = runTest {
        val namespace = "command-handoff-reopen-" + kotlin.random.Random.nextInt()
        try {
            val first = createAppDatabase(namespace)
            try {
                verifyCommandHandoffPreservesBoth(first)
            } finally {
                first.close()
            }
            val reopened = createAppDatabase(namespace)
            try {
                verifyCommandHandoffAfterReopen(reopened)
            } finally {
                reopened.close()
            }
        } finally {
            deleteHandoffDatabase(namespace + ".database").await<JsAny?>()
        }
    }

    private fun withDatabase(block: suspend (AppDatabase) -> Unit) = runTest {
        val namespace = "command-handoff-" + kotlin.random.Random.nextInt()
        val database = createAppDatabase(namespace)
        try {
            block(database)
        } finally {
            database.close()
            deleteHandoffDatabase(namespace + ".database").await<JsAny?>()
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
private external fun deleteHandoffDatabase(name: String): Promise<JsAny?>
