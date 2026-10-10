@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BrowserDashboardStoreTest {
    @Test
    fun transactionalOutboxInRealIndexedDb() = runTest {
        val namespace = "dashboard-test-" + kotlin.random.Random.nextInt()
        val database = createAppDatabase(namespace)
        try {
            verifyDashboardTransactions(database)
            verifyAccountIsolation(database)
            verifyPaginationTransactions(database)
        } finally {
            database.close()
            deleteDatabase("$namespace.database").await<JsAny?>()
        }
    }

    @Test
    fun versionOneUpgradeAndReopenPreserveData() = runTest {
        val namespace = "dashboard-upgrade-" + kotlin.random.Random.nextInt()
        seedVersionOne("$namespace.database").await<JsAny?>()
        var database = createAppDatabase(namespace)
        try {
            assertEquals("existing", database.inbox.observe().first().single().id)
            database.accounts.activate("session-a")
            database.dashboard.replaceContent("session-a", sampleDashboard)
            database.dashboard.setSaved("session-a", ActivityChangeRecord("durable-id", "a", true))
            database.close()
            database = createAppDatabase(namespace)
            assertTrue(database.dashboard.observe("session-a").first()!!.activity.single().saved)
            assertEquals(
                "durable-id",
                database.dashboard.pendingChanges("session-a", 1).single().operationId,
            )
        } finally {
            database.close()
            deleteDatabase("$namespace.database").await<JsAny?>()
        }
    }
}

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 1);
    request.onupgradeneeded = () => request.result.createObjectStore('inbox', {keyPath: 'id'});
    request.onsuccess = () => {
        const db = request.result;
        const tx = db.transaction('inbox', 'readwrite');
        tx.objectStore('inbox').put({id:'existing', title:'Keep me', body:'Message', destination:'inbox', createdAtEpochMillis:1, read:true});
        tx.oncomplete = () => { db.close(); resolve(null); };
        tx.onabort = () => { db.close(); reject(tx.error); };
    };
    request.onerror = () => reject(request.error);
})"""
)
private external fun seedVersionOne(name: String): Promise<JsAny?>

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null); request.onerror = () => reject(request.error);
})"""
)
private external fun deleteDatabase(name: String): Promise<JsAny?>
