@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BrowserDeviceRecordStoreTest {
    @Test
    fun retentionDeduplicatesAndExactCleanupPreservesReplacement() = runTest {
        withDatabase { verifyDeviceRecordRetention(it.deviceRecords) }
    }

    @Test
    fun concurrentInsertionRespectsGlobalCapacity() = runTest {
        withDatabase { verifyDeviceRecordCapacity(it.deviceRecords) }
    }

    @Test
    fun orderedPagesRemainBoundedAndSeparateNamespaces() = runTest {
        withDatabase { verifyDeviceRecordPages(it.deviceRecords) }
    }

    @Test
    fun deviceRecordsSurviveAccountReplacement() = runTest {
        withDatabase { verifyDeviceRecordOwnership(it) }
    }

    @Test
    fun cancelledOperationsCannotReadOrMutateRecords() = runTest {
        withDatabase { verifyDeviceRecordCancellation(it.deviceRecords) }
    }

    @Test
    fun boundsAndRedactedDiagnosticsProtectTheRawBoundary() = runTest {
        withDatabase { verifyDeviceRecordBounds(it.deviceRecords) }
    }

    @Test
    fun concurrentOwnersRetainOneOriginalAndStaleRemovalCannotEraseReplacement() = runTest {
        withLocation { location ->
            val first = open(location)
            val second = open(location)
            try {
                verifyDeviceRecordConcurrentIdentity(first.deviceRecords, second.deviceRecords)
            } finally {
                first.close()
                second.close()
            }
        }
    }

    @Test
    fun deviceRecordsSurviveDatabaseReopenAndExactRemoval() = runTest {
        withLocation { location ->
            var database = open(location)
            val record = DeviceRecord("input", "original", "first", "sealed-original")
            try {
                assertEquals(record, database.deviceRecords.retain(record, 1))
                database.close()
                database = open(location)
                assertEquals(record, database.deviceRecords.get(record.namespace, record.id))
                assertEquals(
                    record,
                    database.deviceRecords.retain(record.copy(payload = "new-ciphertext"), 1),
                )
                assertTrue(database.deviceRecords.remove(record))
                database.close()
                database = open(location)
                assertNull(database.deviceRecords.get(record.namespace, record.id))
                assertTrue(database.deviceRecords.page(record.namespace).isEmpty())
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun versionElevenMigrationPreservesPendingWakeAndInbox() = runTest {
        withLocation { location ->
            val previous = openPreviousDeviceDatabase("$location.database").await<JsAny>()
            try {
                seedDeviceRecordPredecessor(previous).await<JsAny?>()
            } finally {
                closeDeviceRecordPredecessor(previous)
            }
            val database = open(location)
            try {
                val inbox = InboxRecord("retained", "Retained", "Fixture", "inbox", 1)
                val wake = WorkWakeRecord("chat.recovery", "original", true)
                assertEquals(listOf(inbox), database.inbox.observe().first())
                assertEquals(wake, database.workWakes.get(wake.key))
                val record = DeviceRecord("input", "first", "original", "sealed-input")
                assertTrue(database.deviceRecords.page(record.namespace).isEmpty())
                assertEquals(record, database.deviceRecords.retain(record, 1))
                assertEquals(record, database.deviceRecords.get(record.namespace, record.id))
                assertEquals(listOf(inbox), database.inbox.observe().first())
                assertEquals(wake, database.workWakes.get(wake.key))
            } finally {
                database.close()
            }
        }
    }

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        withLocation { location ->
            val database = open(location)
            try {
                block(database)
            } finally {
                database.close()
            }
        }
    }
}

private fun open(location: String) = createAppDatabase(location)

private suspend fun withLocation(block: suspend (String) -> Unit) {
    val name = "device-records-" + kotlin.random.Random.nextInt()
    try {
        block(name)
    } finally {
        deleteDeviceRecordDatabase("$name.database").await<JsAny?>()
    }
}

@JsFun(
    """(name) => new Promise((resolve, reject) => { const request = indexedDB.deleteDatabase(name); request.onsuccess = () => resolve(null); request.onerror = () => reject(request.error); request.onblocked = () => reject(new Error('Device record database still open')); })"""
)
private external fun deleteDeviceRecordDatabase(name: String): Promise<JsAny?>

@JsFun(
    """(db) => new Promise((resolve, reject) => {
    const tx = db.transaction(['inbox', 'work_wakes'], 'readwrite');
    tx.objectStore('inbox').put({id:'retained', title:'Retained', body:'Fixture', destination:'inbox', createdAtEpochMillis:1, read:false});
    tx.objectStore('work_wakes').put({key:'chat.recovery', generation:'original', pending:true});
    tx.oncomplete = () => resolve(null);
    tx.onabort = () => reject(tx.error || new Error('Device record predecessor seed aborted'));
    tx.onerror = () => reject(tx.error);
})"""
)
private external fun seedDeviceRecordPredecessor(db: JsAny): Promise<JsAny?>

@JsFun("(db) => db.close()") private external fun closeDeviceRecordPredecessor(db: JsAny)
