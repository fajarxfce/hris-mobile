@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.securestorage

import kotlin.js.Promise
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Explicit persistent browser storage for a content-key record. Authentication remains memory-only.
 * A non-extractable WebCrypto key and its authenticated ciphertext commit as one IndexedDB value.
 * Same-origin script can use that key; this is not protection from a compromised origin or XSS.
 */
class BrowserProtectedCredentialStore(namespace: String, recordName: String) :
    AtomicCredentialStore {
    init {
        require(namespace.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,127}")))
        require(recordName.matches(Regex("[a-z][a-z0-9_.-]{0,63}")))
    }

    override val persistent = true
    private val name = "$namespace.credentials.$recordName"
    private val connection = lazy { openCredentialDatabase(name) }
    private var closed = false

    override suspend fun read(): String? {
        val database = database()
        val value = credentialOperation(database, name, "read", null).await<JsString?>()?.toString()
        currentCoroutineContext().ensureActive()
        check(!closed) { "Credential store is closed." }
        return value
    }

    override suspend fun write(value: String?) {
        require(value == null || value.encodeToByteArray().size <= 65_536)
        val database = database()
        withContext(NonCancellable) {
            credentialOperation(database, name, "write", value).await<JsString?>()
        }
        currentCoroutineContext().ensureActive()
        check(!closed) { "Credential store is closed." }
    }

    override suspend fun writeIfAbsent(value: String): String {
        require(value.encodeToByteArray().size <= 65_536)
        val database = database()
        val stored =
            withContext(NonCancellable) {
                credentialOperation(database, name, "create", value).await<JsString?>()?.toString()
            }
        currentCoroutineContext().ensureActive()
        check(!closed) { "Credential store is closed." }
        return requireNotNull(stored) { "Credential creation returned no record." }
    }

    private suspend fun database(): JsAny {
        currentCoroutineContext().ensureActive()
        check(!closed) { "Credential store is closed." }
        val database = connection.value.await<JsAny>()
        check(!closed) { "Credential store is closed." }
        return database
    }

    override fun close() {
        if (closed) return
        closed = true
        if (connection.isInitialized()) closeCredentialDatabase(connection.value)
    }
}

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    let settled = false;
    const request = indexedDB.open(name, 1);
    const timer = setTimeout(() => {
        settled = true;
        reject(new Error('Credential database open timed out.'));
    }, 8000);
    const fail = error => {
        clearTimeout(timer); settled = true; reject(error);
    };
    request.onupgradeneeded = () => {
        if (settled) { request.transaction.abort(); return; }
        request.result.createObjectStore('credential');
    };
    request.onsuccess = () => {
        clearTimeout(timer);
        if (settled) { request.result.close(); return; }
        settled = true;
        request.result.onversionchange = () => request.result.close();
        resolve(request.result);
    };
    request.onerror = () => fail(request.error);
    request.onblocked = () => fail(new Error('Credential database open is blocked.'));
})"""
)
private external fun openCredentialDatabase(name: String): Promise<JsAny>

@JsFun(
    """async (db, purpose, mode, value) => {
    if (!globalThis.isSecureContext || !globalThis.crypto?.subtle) {
        throw new Error('Persistent credentials require a secure context.');
    }
    const aad = new TextEncoder().encode('credential:v1:' + purpose);
    let candidate = null;
    if (mode !== 'read' && value !== null) {
        const bytes = new TextEncoder().encode(value);
        try {
            if (bytes.byteLength > 65536) throw new Error('Credential record is too large.');
            const key = await crypto.subtle.generateKey({name:'AES-GCM', length:256}, false, ['encrypt','decrypt']);
            const nonce = crypto.getRandomValues(new Uint8Array(12));
            const ciphertext = await crypto.subtle.encrypt({name:'AES-GCM', iv:nonce, additionalData:aad, tagLength:128}, key, bytes);
            candidate = {format:1, key, nonce, ciphertext};
        } finally { bytes.fill(0); }
    }
    const record = await new Promise((resolve, reject) => {
        const tx = db.transaction('credential', mode === 'read' ? 'readonly' : 'readwrite');
        const timer = setTimeout(() => { try { tx.abort(); } catch (_) {} }, 5000);
        const store = tx.objectStore('credential');
        let selected = null;
        if (mode === 'write') {
            if (candidate === null) store.delete('record');
            else store.put(candidate, 'record');
        } else {
            const request = store.get('record');
            request.onsuccess = () => {
                if (request.result !== undefined) selected = request.result;
                else if (mode === 'create') {
                    store.add(candidate, 'record'); selected = candidate;
                }
            };
        }
        tx.oncomplete = () => { clearTimeout(timer); resolve(selected); };
        tx.onabort = () => { clearTimeout(timer); reject(tx.error || new Error('Credential transaction failed.')); };
    });
    if (record === null) return null;
    if (record.format !== 1 || !(record.key instanceof CryptoKey) || record.key.extractable ||
        record.key.type !== 'secret' || record.key.algorithm.name !== 'AES-GCM' ||
        record.key.algorithm.length !== 256 || record.key.usages.length !== 2 ||
        !record.key.usages.includes('encrypt') || !record.key.usages.includes('decrypt') ||
        !(record.nonce instanceof Uint8Array) || record.nonce.byteLength !== 12 ||
        !(record.ciphertext instanceof ArrayBuffer) || record.ciphertext.byteLength < 16 ||
        record.ciphertext.byteLength > 65552) {
        throw new Error('Unsupported protected credential record.');
    }
    const plaintext = new Uint8Array(await crypto.subtle.decrypt(
        {name:'AES-GCM', iv:record.nonce, additionalData:aad, tagLength:128}, record.key, record.ciphertext));
    try { return new TextDecoder('utf-8', {fatal:true}).decode(plaintext); }
    finally { plaintext.fill(0); }
}"""
)
private external fun credentialOperation(
    database: JsAny,
    purpose: String,
    mode: String,
    value: String?,
): Promise<JsString?>

@JsFun("(pending) => { pending.then(db => db.close(), () => {}); }")
private external fun closeCredentialDatabase(database: Promise<JsAny>)
