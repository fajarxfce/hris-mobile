@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BrowserWorkWakeStoreTest {
    @Test
    fun collapseAndAcknowledgmentKeepTheNewestGeneration() = runTest {
        withDatabase { verifyWorkWakeGenerations(it.workWakes) }
    }

    @Test
    fun concurrentNewKeysCannotExceedCapacityButExistingKeysCanAdvance() = runTest {
        withDatabase { verifyWorkWakeCapacity(it.workWakes) }
    }

    @Test
    fun anOldCompletionCannotEraseAConcurrentWake() = runTest {
        withDatabase { verifyWorkWakeAcknowledgmentRace(it.workWakes) }
    }

    @Test
    fun schedulingMetadataSurvivesAccountReplacementWithoutCarryingAccountIdentity() = runTest {
        withDatabase { verifyWorkWakeGlobalOwnership(it) }
    }

    @Test
    fun cancellationAndInvalidBoundsCannotCreateAWake() = runTest {
        withDatabase { verifyWorkWakeCancellationAndBounds(it.workWakes) }
    }

    @Test
    fun pendingAndAcknowledgedGenerationsSurviveClosingAndReopeningTheDatabase() = runTest {
        val namespace = "work-wake-reopen-" + kotlin.random.Random.nextInt()
        var database = createAppDatabase(namespace)
        val pending = WorkWakeRecord("chat.recovery", "original", true)
        try {
            assertEquals(pending, database.workWakes.request(pending.key, pending.generation, 1))
            database.close()
            database = createAppDatabase(namespace)
            assertEquals(pending, database.workWakes.get(pending.key))
            assertTrue(database.workWakes.acknowledge(pending.key, pending.generation))
            database.close()
            database = createAppDatabase(namespace)
            assertEquals(pending.copy(pending = false), database.workWakes.get(pending.key))
            assertEquals(
                pending.copy(pending = false),
                database.workWakes.request(pending.key, pending.generation, 1),
            )
        } finally {
            database.close()
            deleteWakeDatabase("$namespace.database").await<JsAny?>()
        }
    }

    @Test
    fun versionTenUpgradeAddsWakesWhilePreservingTheExistingInbox() = runTest {
        val namespace = "work-wake-upgrade-" + kotlin.random.Random.nextInt()
        val old = openPreviousWakeDatabase("$namespace.database").await<JsAny>()
        try {
            seedPreviousWakeInbox(old).await<JsAny?>()
        } finally {
            closePreviousWakeDatabase(old)
        }
        val database = createAppDatabase(namespace)
        try {
            val original = InboxRecord("retained", "Retained", "Fixture", "inbox", 1)
            assertEquals(listOf(original), database.inbox.observe().first())
            assertNull(database.workWakes.get("chat.recovery"))
            assertNotNull(database.workWakes.request("chat.recovery", "first", 1))
            assertEquals(listOf(original), database.inbox.observe().first())
        } finally {
            database.close()
            deleteWakeDatabase("$namespace.database").await<JsAny?>()
        }
    }

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val namespace = "work-wake-contract-" + kotlin.random.Random.nextInt()
        val database = createAppDatabase(namespace)
        try {
            block(database)
        } finally {
            database.close()
            deleteWakeDatabase("$namespace.database").await<JsAny?>()
        }
    }
}

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Wake database close was blocked'));
})"""
)
private external fun deleteWakeDatabase(name: String): Promise<JsAny?>

@JsFun(
    """(db) => new Promise((resolve, reject) => {
    const tx = db.transaction('inbox', 'readwrite');
    tx.objectStore('inbox').put({id:'retained', title:'Retained', body:'Fixture', destination:'inbox', createdAtEpochMillis:1, read:false});
    tx.oncomplete = () => resolve(null);
    tx.onabort = () => reject(tx.error || new Error('Legacy inbox seed aborted'));
})"""
)
private external fun seedPreviousWakeInbox(db: JsAny): Promise<JsAny?>

@JsFun("(db) => db.close()") private external fun closePreviousWakeDatabase(db: JsAny)
