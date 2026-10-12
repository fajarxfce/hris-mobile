@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

internal class IndexedDbBlobStore(
    private val changes: MutableSharedFlow<Unit>,
    private val database: suspend () -> JsAny,
) : BlobStore {
    private val codec = Json { encodeDefaults = true }

    override fun observe(scope: BlobScope, partition: String, after: String?, limit: Int) =
        changes
            .map {
                validateBlobScope(scope, partition)
                validateBlobPage(after, limit)
                Json.decodeFromString<List<BlobRecord>>(
                    blobPage(database(), scope.sessionId, scope.accountKey, partition, after, limit)
                        .await<JsString>()
                        .toString()
                )
            }
            .distinctUntilChanged()

    override suspend fun get(scope: BlobScope, partition: String, id: String): BlobRecord? {
        validateBlobScope(scope, partition)
        validateBlobId(id)
        return Json.decodeFromString(
            blobRecord(database(), scope.sessionId, scope.accountKey, partition, id)
                .await<JsString>()
                .toString()
        )
    }

    override suspend fun insert(
        scope: BlobScope,
        record: BlobRecord,
        maxBytes: Long,
        maxItems: Int,
    ): BlobInsertResult {
        validateBlobInsert(scope, record, maxBytes, maxItems)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val result =
                BlobInsertResult.valueOf(
                    insertBlob(
                            db,
                            scope.sessionId,
                            scope.accountKey,
                            codec.encodeToString(record),
                            maxBytes.toDouble(),
                            maxItems,
                            MAX_BLOB_TOTAL_BYTES.toDouble(),
                            MAX_BLOB_TOTAL_ITEMS,
                        )
                        .await<JsString>()
                        .toString()
                )
            if (result == BlobInsertResult.Stored) changes.tryEmit(Unit)
            result
        }
    }

    override suspend fun append(
        scope: BlobScope,
        partition: String,
        id: String,
        expectedRevision: Long,
        chunk: BlobChunkRecord,
    ): Boolean {
        validateBlobScope(scope, partition)
        validateBlobId(id)
        validateBlobRevision(expectedRevision)
        validateBlobChunk(chunk)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val changed =
                appendBlob(
                        db,
                        scope.sessionId,
                        scope.accountKey,
                        partition,
                        id,
                        expectedRevision.toDouble(),
                        codec.encodeToString(chunk),
                        BLOB_BLOCK_BYTES,
                    )
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override suspend fun complete(
        scope: BlobScope,
        partition: String,
        id: String,
        expectedRevision: Long,
        completion: String,
    ): Boolean {
        validateBlobScope(scope, partition)
        validateBlobId(id)
        validateBlobRevision(expectedRevision)
        validateBlobEnvelope(completion)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val changed =
                completeBlob(
                        db,
                        scope.sessionId,
                        scope.accountKey,
                        partition,
                        id,
                        expectedRevision.toDouble(),
                        completion,
                    )
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override suspend fun updateState(
        scope: BlobScope,
        partition: String,
        id: String,
        expectedRevision: Long,
        state: String,
    ): Boolean {
        validateBlobScope(scope, partition)
        validateBlobId(id)
        validateBlobRevision(expectedRevision)
        validateBlobEnvelope(state)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val changed =
                changeBlobState(
                        db,
                        scope.sessionId,
                        scope.accountKey,
                        partition,
                        id,
                        expectedRevision.toDouble(),
                        state,
                    )
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override suspend fun chunk(
        scope: BlobScope,
        partition: String,
        id: String,
        offset: Long,
    ): BlobChunkRecord? {
        validateBlobScope(scope, partition)
        validateBlobId(id)
        validateBlobOffset(offset)
        return Json.decodeFromString(
            blobChunk(
                    database(),
                    scope.sessionId,
                    scope.accountKey,
                    partition,
                    id,
                    offset.toDouble(),
                )
                .await<JsString>()
                .toString()
        )
    }

    override suspend fun remove(
        scope: BlobScope,
        partition: String,
        id: String,
        expectedRevision: Long,
    ): Boolean {
        validateBlobScope(scope, partition)
        validateBlobId(id)
        validateBlobRevision(expectedRevision)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val changed =
                removeBlob(
                        db,
                        scope.sessionId,
                        scope.accountKey,
                        partition,
                        id,
                        expectedRevision.toDouble(),
                    )
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override suspend fun containsRecords() = anyBlobs(database()).await<JsBoolean>().toBoolean()
}

@JsFun(
    """(db, session, account, partition, after, limit) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'durable_blobs'], 'readonly');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    let rows = [];
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const range = IDBKeyRange.bound([account, partition, after ?? ''], [account, partition, []], after !== null, true);
        const read = tx.objectStore('durable_blobs').getAll(range, limit);
        read.onsuccess = () => { rows = read.result.map(({accountKey, ...entry}) => entry); };
    };
    tx.oncomplete = () => resolve(JSON.stringify(rows));
    tx.onabort = () => reject(tx.error || new Error('Blob read failed.'));
})"""
)
private external fun blobPage(
    db: JsAny,
    session: String,
    account: String,
    partition: String,
    after: String?,
    limit: Int,
): Promise<JsString>

@JsFun(
    """(db, session, account, partition, id) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'durable_blobs'], 'readonly');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    let value = null;
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const read = tx.objectStore('durable_blobs').get([account, partition, id]);
        read.onsuccess = () => { if (read.result) { const {accountKey, ...entry} = read.result; value = entry; } };
    };
    tx.oncomplete = () => resolve(JSON.stringify(value));
    tx.onabort = () => reject(tx.error || new Error('Blob read failed.'));
})"""
)
private external fun blobRecord(
    db: JsAny,
    session: String,
    account: String,
    partition: String,
    id: String,
): Promise<JsString>

@JsFun(
    """(db, session, account, json, maxBytes, maxItems, totalBytes, totalItems) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'durable_blobs'], 'readwrite');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    const store = tx.objectStore('durable_blobs'), entry = {...JSON.parse(json), accountKey:account};
    let result = 'ScopeChanged';
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const previous = store.get([account, entry.partition, entry.id]);
        previous.onsuccess = () => {
            if (previous.result) {
                if (previous.result.size !== entry.size || previous.result.metadata !== entry.metadata) { tx.abort(); return; }
                result = 'AlreadyStored'; return;
            }
            const all = store.getAll(null, totalItems + 1);
            all.onsuccess = () => {
                const rows = all.result, owned = rows.filter(row => row.accountKey === account);
                const reserved = rows.reduce((sum, row) => sum + row.size, 0);
                const ownedBytes = owned.reduce((sum, row) => sum + row.size, 0);
                if (rows.length >= totalItems || owned.length >= maxItems || entry.size > totalBytes - reserved || entry.size > maxBytes - ownedBytes) {
                    result = 'CapacityExceeded'; return;
                }
                store.add(entry); result = 'Stored';
            };
        };
    };
    tx.oncomplete = () => resolve(result);
    tx.onabort = () => reject(tx.error || new Error('Blob creation failed.'));
})"""
)
private external fun insertBlob(
    db: JsAny,
    session: String,
    account: String,
    record: String,
    maxBytes: Double,
    maxItems: Int,
    totalBytes: Double,
    totalItems: Int,
): Promise<JsString>

@JsFun(
    """(db, session, account, partition, id, revision, json, blockBytes) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'durable_blobs', 'durable_blob_chunks'], 'readwrite');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    const store = tx.objectStore('durable_blobs'), chunk = JSON.parse(json);
    let changed = false;
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const read = store.get([account, partition, id]);
        read.onsuccess = () => {
            const current = read.result;
            if (!current || current.revision !== revision || current.completion !== null) return;
            if (current.storedBytes !== chunk.offset || chunk.length !== Math.min(blockBytes, current.size - current.storedBytes)) { tx.abort(); return; }
            tx.objectStore('durable_blob_chunks').add({...chunk, accountKey:account, partition, id});
            store.put({...current, storedBytes:current.storedBytes + chunk.length, revision:revision + 1});
            changed = true;
        };
    };
    tx.oncomplete = () => resolve(changed);
    tx.onabort = () => reject(tx.error || new Error('Blob append failed.'));
})"""
)
private external fun appendBlob(
    db: JsAny,
    session: String,
    account: String,
    partition: String,
    id: String,
    revision: Double,
    chunk: String,
    blockBytes: Int,
): Promise<JsBoolean>

@JsFun(
    """(db, session, account, partition, id, revision, completion) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'durable_blobs'], 'readwrite');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    const store = tx.objectStore('durable_blobs'); let changed = false;
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const read = store.get([account, partition, id]);
        read.onsuccess = () => {
            const current = read.result;
            if (!current || current.revision !== revision || current.completion !== null || current.storedBytes !== current.size) return;
            store.put({...current, completion, revision:revision + 1}); changed = true;
        };
    };
    tx.oncomplete = () => resolve(changed);
    tx.onabort = () => reject(tx.error || new Error('Blob completion failed.'));
})"""
)
private external fun completeBlob(
    db: JsAny,
    session: String,
    account: String,
    partition: String,
    id: String,
    revision: Double,
    completion: String,
): Promise<JsBoolean>

@JsFun(
    """(db, session, account, partition, id, offset) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'durable_blob_chunks'], 'readonly');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    let value = null;
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const read = tx.objectStore('durable_blob_chunks').get([account, partition, id, offset]);
        read.onsuccess = () => { if (read.result) { const {accountKey, partition, id, ...chunk} = read.result; value = chunk; } };
    };
    tx.oncomplete = () => resolve(JSON.stringify(value));
    tx.onabort = () => reject(tx.error || new Error('Blob block read failed.'));
})"""
)
private external fun blobChunk(
    db: JsAny,
    session: String,
    account: String,
    partition: String,
    id: String,
    offset: Double,
): Promise<JsString>

@JsFun(
    """(db, session, account, partition, id, revision) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'durable_blobs', 'durable_blob_chunks'], 'readwrite');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    const store = tx.objectStore('durable_blobs'); let changed = false;
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const read = store.get([account, partition, id]);
        read.onsuccess = () => {
            if (!read.result || read.result.revision !== revision) return;
            tx.objectStore('durable_blob_chunks').delete(IDBKeyRange.bound([account, partition, id, 0], [account, partition, id, []], false, true));
            store.delete([account, partition, id]); changed = true;
        };
    };
    tx.oncomplete = () => resolve(changed);
    tx.onabort = () => reject(tx.error || new Error('Blob deletion failed.'));
})"""
)
private external fun removeBlob(
    db: JsAny,
    session: String,
    account: String,
    partition: String,
    id: String,
    revision: Double,
): Promise<JsBoolean>

@JsFun(
    """(db) => new Promise((resolve, reject) => {
    const tx = db.transaction('durable_blobs', 'readonly');
    const read = tx.objectStore('durable_blobs').openKeyCursor();
    tx.oncomplete = () => resolve(!!read.result);
    tx.onabort = () => reject(tx.error || new Error('Blob existence read failed.'));
})"""
)
private external fun anyBlobs(db: JsAny): Promise<JsBoolean>

@JsFun(
    """(db, session, account, partition, id, revision, state) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'durable_blobs'], 'readwrite');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    const store = tx.objectStore('durable_blobs'); let changed = false;
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const read = store.get([account, partition, id]);
        read.onsuccess = () => {
            const current = read.result;
            if (!current || current.revision !== revision || current.completion === null) return;
            store.put({...current, state, revision:revision + 1}); changed = true;
        };
    };
    tx.oncomplete = () => resolve(changed);
    tx.onabort = () => reject(tx.error || new Error('Blob checkpoint failed.'));
})"""
)
private external fun changeBlobState(
    db: JsAny,
    session: String,
    account: String,
    partition: String,
    id: String,
    revision: Double,
    state: String,
): Promise<JsBoolean>
