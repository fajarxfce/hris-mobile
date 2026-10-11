@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.securestorage

import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class BrowserProtectedCredentialStoreTest {
    @Test
    fun simultaneousOwnersKeepOneNonExtractableKeyAcrossReopening() = runTest {
        val fixture = CredentialFixture()
        try {
            val first = fixture.open()
            val second = fixture.open()
            val values =
                listOf(
                        async { first.writeIfAbsent("first private key") },
                        async { second.writeIfAbsent("second private key") },
                    )
                    .awaitAll()
            assertEquals(1, values.distinct().size)
            assertEquals(values.first(), first.read())
            assertTrue(protectedRecord(fixture.database).await<JsBoolean>().toBoolean())
            first.close()
            second.close()
            val reopened = fixture.open()
            assertEquals(values.first(), reopened.read())
            assertEquals(values.first(), reopened.writeIfAbsent("replacement"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ordinaryWritesAndClearsRemainExplicitAndBoundedByUtf8Bytes() = runTest {
        val fixture = CredentialFixture()
        try {
            val store = fixture.open()
            assertNull(store.read())
            val maximum = "é".repeat(32_768)
            store.write(maximum)
            assertEquals(maximum, store.read())
            assertFailsWith<IllegalArgumentException> { store.write(maximum + "é") }
            assertEquals(maximum, store.read())
            store.write(null)
            assertNull(store.read())
            assertEquals("", store.writeIfAbsent(""))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun copiedRecordsCannotBeOpenedUnderAnotherNamespace() = runTest {
        val first = CredentialFixture()
        val second = CredentialFixture()
        try {
            first.open().writeIfAbsent("private fixture")
            val destination = second.open()
            destination.read()
            modifyRecord(first.database, second.database, false).await<JsAny?>()
            val result = safeStorageCall(onException = {}) { destination.read() }
            assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(result).failure.kind)
            assertFalse(result.failure.message.contains("private fixture"))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun corruptedExistingRecordsAreNotReplacedByCreation() = runTest {
        val fixture = CredentialFixture()
        try {
            val store = fixture.open()
            store.writeIfAbsent("private fixture")
            modifyRecord(fixture.database, fixture.database, true).await<JsAny?>()
            assertIs<AppResult.Failed>(safeStorageCall(onException = {}) { store.read() })
            assertIs<AppResult.Failed>(
                safeStorageCall(onException = {}) { store.writeIfAbsent("replacement") }
            )
            assertIs<AppResult.Failed>(safeStorageCall(onException = {}) { fixture.open().read() })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun closingDuringOpenClosesTheLateConnectionAndRejectsFurtherUse() = runTest {
        val fixture = CredentialFixture()
        try {
            val store = fixture.open()
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    safeStorageCall(onException = {}) { store.read() }
                }
            store.close()
            assertIs<AppResult.Failed>(pending.await())
            assertFailsWith<IllegalStateException> { store.read() }
            assertFailsWith<IllegalStateException> { store.writeIfAbsent("late") }
            store.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellationLeavesAnAbsentOrCompleteRecordThatCanBeRecovered() = runTest {
        val fixture = CredentialFixture()
        try {
            val store = fixture.open()
            val cancelled = Job().apply { cancel() }
            assertFailsWith<CancellationException> {
                withContext(cancelled) { store.writeIfAbsent("never admitted") }
            }
            assertNull(store.read())
            val pending =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    store.writeIfAbsent("retained fixture")
                }
            yield()
            pending.cancelAndJoin()
            val retained = store.read()
            assertTrue(retained == null || retained == "retained fixture")
            assertEquals(retained ?: "next fixture", store.writeIfAbsent("next fixture"))
        } finally {
            fixture.close()
        }
    }
}

private class CredentialFixture {
    private val namespace =
        "credential-fixture-" + Random.nextInt().toUInt() + "-" + Random.nextInt().toUInt()
    val database = "$namespace.credentials.record"
    private val owners = mutableListOf<BrowserProtectedCredentialStore>()

    fun open() = BrowserProtectedCredentialStore(namespace, "record").also { owners += it }

    suspend fun close() {
        owners.forEach { it.close() }
        deleteCredentialFixture(database).await<JsAny?>()
    }
}

@JsFun(
    """async (name) => {
    const db = await new Promise((resolve, reject) => {
        const request = indexedDB.open(name, 1);
        request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    try {
        const record = await new Promise((resolve, reject) => {
            const tx = db.transaction('credential', 'readonly');
            const request = tx.objectStore('credential').get('record');
            tx.oncomplete = () => resolve(request.result); tx.onabort = () => reject(tx.error);
        });
        if (record.key.extractable || !(record.ciphertext instanceof ArrayBuffer) ||
            typeof record.value === 'string' || Object.keys(record).length !== 4) return false;
        try { await crypto.subtle.exportKey('raw', record.key); return false; }
        catch (_) { return true; }
    } finally { db.close(); }
}"""
)
private external fun protectedRecord(name: String): Promise<JsBoolean>

@JsFun(
    """async (source, target, corrupt) => {
    const open = name => new Promise((resolve, reject) => {
        const request = indexedDB.open(name, 1);
        request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    const first = await open(source), second = await open(target);
    try {
        const record = await new Promise((resolve, reject) => {
            const tx = first.transaction('credential', 'readonly');
            const request = tx.objectStore('credential').get('record');
            tx.oncomplete = () => resolve(request.result); tx.onabort = () => reject(tx.error);
        });
        if (corrupt) new Uint8Array(record.ciphertext)[0] ^= 1;
        await new Promise((resolve, reject) => {
            const tx = second.transaction('credential', 'readwrite');
            tx.objectStore('credential').put(record, 'record');
            tx.oncomplete = () => resolve(null); tx.onabort = () => reject(tx.error);
        });
        return null;
    } finally { first.close(); second.close(); }
}"""
)
private external fun modifyRecord(source: String, target: String, corrupt: Boolean): Promise<JsAny?>

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null); request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Credential fixture retained an open connection.'));
})"""
)
private external fun deleteCredentialFixture(name: String): Promise<JsAny?>
