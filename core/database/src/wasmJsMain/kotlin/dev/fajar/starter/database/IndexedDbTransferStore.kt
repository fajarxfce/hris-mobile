@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class IndexedDbTransferStore(
    private val changes: MutableSharedFlow<Unit>,
    private val database: suspend () -> JsAny,
) : TransferStore {
    private val codec = Json { encodeDefaults = true }

    override fun observe(sessionId: String) =
        changes.map {
            Json.decodeFromString<List<TransferRecord>>(
                readTransfers(database(), sessionId).await<JsString>().toString()
            )
        }

    override suspend fun get(sessionId: String, id: String) =
        Json.decodeFromString<TransferRecord?>(
            readTransfer(database(), sessionId, id).await<JsString>().toString()
        )

    override suspend fun create(
        sessionId: String,
        record: TransferRecord,
        maxBytes: Long,
        maxItems: Int,
    ): Boolean {
        require(record.size >= 0 && record.version == 0L && maxBytes > 0 && maxItems > 0)
        val applied =
            createTransfer(
                    database(),
                    sessionId,
                    codec.encodeToString(record),
                    maxBytes.toDouble(),
                    maxItems,
                )
                .await<JsBoolean>()
                .toBoolean()
        if (applied) changes.tryEmit(Unit)
        return applied
    }

    override suspend fun replace(
        sessionId: String,
        expectedVersion: Long,
        record: TransferRecord,
        chunk: TransferChunkRecord?,
        clearContent: Boolean,
    ): Boolean {
        if (chunk != null)
            require(
                chunk.offset >= 0 &&
                    chunk.bytes.isNotEmpty() &&
                    chunk.bytes.size <= MAX_STORED_TRANSFER_CHUNK_BYTES &&
                    chunk.offset + chunk.bytes.size <= record.size
            )
        val applied =
            replaceTransfer(
                    database(),
                    sessionId,
                    expectedVersion.toDouble(),
                    codec.encodeToString(record.copy(version = expectedVersion + 1)),
                    chunk?.offset?.toDouble(),
                    chunk?.bytes?.let { Base64.encode(it) },
                    clearContent,
                )
                .await<JsBoolean>()
                .toBoolean()
        if (applied) changes.tryEmit(Unit)
        return applied
    }

    override suspend fun chunk(sessionId: String, id: String, offset: Long): TransferChunkRecord? =
        Json.decodeFromString<BrowserChunk?>(
                readTransferChunk(database(), sessionId, id, offset.toDouble())
                    .await<JsString>()
                    .toString()
            )
            ?.let { TransferChunkRecord(it.offset, Base64.decode(it.content)) }

    override suspend fun remove(sessionId: String, id: String, expectedVersion: Long): Boolean {
        val applied =
            removeTransfer(database(), sessionId, id, expectedVersion.toDouble())
                .await<JsBoolean>()
                .toBoolean()
        if (applied) changes.tryEmit(Unit)
        return applied
    }
}

@Serializable private data class BrowserChunk(val offset: Long, val content: String)

@JsFun(
    """(db, scope) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'transfers'], 'readonly');
    const owner = tx.objectStore('account_scope').get('current'), rows = tx.objectStore('transfers').getAll();
    tx.oncomplete = () => resolve(JSON.stringify(owner.result === scope ? rows.result.sort((a,b) => a.createdAtEpochMillis - b.createdAtEpochMillis || a.id.localeCompare(b.id)) : []));
    tx.onabort = () => reject(tx.error || new Error('Transfer read failed.'));
})"""
)
private external fun readTransfers(db: JsAny, scope: String): Promise<JsString>

@JsFun(
    """(db, scope, id) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'transfers'], 'readonly');
    const owner = tx.objectStore('account_scope').get('current'), row = tx.objectStore('transfers').get(id);
    tx.oncomplete = () => resolve(JSON.stringify(owner.result === scope ? row.result || null : null));
    tx.onabort = () => reject(tx.error || new Error('Transfer read failed.'));
})"""
)
private external fun readTransfer(db: JsAny, scope: String, id: String): Promise<JsString>

@JsFun(
    """(db, scope, json, maxBytes, maxItems) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'transfers'], 'readwrite');
    const owner = tx.objectStore('account_scope').get('current'), store = tx.objectStore('transfers'), rows = store.getAll();
    const row = JSON.parse(json); let applied = false;
    rows.onsuccess = () => {
        if (owner.result !== scope || rows.result.length >= maxItems || rows.result.reduce((sum, row) => sum + row.size, 0) + row.size > maxBytes) return;
        store.add(row); applied = true;
    };
    tx.oncomplete = () => resolve(applied);
    tx.onabort = () => reject(tx.error || new Error('Transfer creation failed.'));
})"""
)
private external fun createTransfer(
    db: JsAny,
    scope: String,
    json: String,
    maxBytes: Double,
    maxItems: Int,
): Promise<JsBoolean>

@JsFun(
    """(db, scope, version, json, offset, content, clear) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'transfers', 'transfer_chunks'], 'readwrite');
    const owner = tx.objectStore('account_scope').get('current'), store = tx.objectStore('transfers'), row = JSON.parse(json), current = store.get(row.id);
    let applied = false;
    current.onsuccess = () => {
        if (owner.result !== scope || !current.result || current.result.version !== version) return;
        if (current.result.size !== row.size) { tx.abort(); return; }
        const chunks = tx.objectStore('transfer_chunks');
        if (clear) chunks.delete(IDBKeyRange.bound([row.id, 0], [row.id, Number.MAX_SAFE_INTEGER]));
        store.put(row);
        if (content !== null) chunks.put({transferId: row.id, offset, content});
        applied = true;
    };
    tx.oncomplete = () => resolve(applied);
    tx.onabort = () => reject(tx.error || new Error('Transfer checkpoint failed.'));
})"""
)
private external fun replaceTransfer(
    db: JsAny,
    scope: String,
    version: Double,
    json: String,
    offset: Double?,
    content: String?,
    clear: Boolean,
): Promise<JsBoolean>

@JsFun(
    """(db, scope, id, offset) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'transfer_chunks'], 'readonly');
    const owner = tx.objectStore('account_scope').get('current');
    const row = tx.objectStore('transfer_chunks').openCursor(IDBKeyRange.bound([id, 0], [id, offset]), 'prev');
    tx.oncomplete = () => resolve(JSON.stringify(owner.result === scope && row.result ? {offset:row.result.value.offset, content:row.result.value.content} : null));
    tx.onabort = () => reject(tx.error || new Error('Transfer content read failed.'));
})"""
)
private external fun readTransferChunk(
    db: JsAny,
    scope: String,
    id: String,
    offset: Double,
): Promise<JsString>

@JsFun(
    """(db, scope, id, version) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'transfers', 'transfer_chunks'], 'readwrite');
    const owner = tx.objectStore('account_scope').get('current'), store = tx.objectStore('transfers'), current = store.get(id);
    let applied = false;
    current.onsuccess = () => {
        if (owner.result !== scope || !current.result || current.result.version !== version) return;
        store.delete(id);
        tx.objectStore('transfer_chunks').delete(IDBKeyRange.bound([id, 0], [id, Number.MAX_SAFE_INTEGER]));
        applied = true;
    };
    tx.oncomplete = () => resolve(applied);
    tx.onabort = () => reject(tx.error || new Error('Transfer deletion failed.'));
})"""
)
private external fun removeTransfer(
    db: JsAny,
    scope: String,
    id: String,
    version: Double,
): Promise<JsBoolean>
