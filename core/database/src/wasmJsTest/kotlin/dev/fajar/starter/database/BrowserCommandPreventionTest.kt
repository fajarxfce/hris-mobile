@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserCommandPreventionTest {
    @Test fun identityAndOwnership() = withDatabase { verifyAbsentArchiveIdentityAndOwnership(it) }

    @Test
    fun conflictsAndCapacity() = withDatabase { verifyAbsentArchiveActiveConflictAndCapacity(it) }

    private fun withDatabase(block: suspend (AppDatabase) -> Unit) = runTest {
        val namespace = "command-prevention-" + kotlin.random.Random.nextInt()
        val database = createAppDatabase(namespace)
        try {
            block(database)
        } finally {
            database.close()
            deletePreventionDatabase(namespace + ".database").await<JsAny?>()
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
private external fun deletePreventionDatabase(name: String): Promise<JsAny?>
