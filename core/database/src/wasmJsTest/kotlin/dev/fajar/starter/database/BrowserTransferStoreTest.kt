@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserTransferStoreTest {
    @Test
    fun transactionsAndReopen() = runTest {
        val namespace = "transfer-test-" + kotlin.random.Random.nextInt()
        var database = createAppDatabase(namespace)
        try {
            verifyTransferTransactions(database)
            database.accounts.activate("resume")
            val row = TransferRecord("resume", "Download", "example", "text/plain", 3, 1)
            assertTrue(database.transfers.create("resume", row, 100, 10))
            assertTrue(
                database.transfers.replace(
                    "resume",
                    0,
                    row.copy(offset = 3, storedBytes = 3),
                    TransferChunkRecord(0, byteArrayOf(1, 2, 3)),
                )
            )
            database.close()
            database = createAppDatabase(namespace)
            assertEquals(3, database.transfers.get("resume", "resume")!!.offset)
            assertContentEquals(
                byteArrayOf(1, 2, 3),
                database.transfers.chunk("resume", "resume", 0)!!.bytes,
            )
        } finally {
            database.close()
            deleteTransferDatabase("$namespace.database").await<JsAny?>()
        }
    }
}

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null); request.onerror = () => reject(request.error);
})"""
)
private external fun deleteTransferDatabase(name: String): Promise<JsAny?>
