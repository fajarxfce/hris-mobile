@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

fun createAppDatabase(namespace: String): AppDatabase = IndexedDbAppDatabase("$namespace.database")

private class IndexedDbAppDatabase(name: String) : AppDatabase {
    private val connection = lazy { openAppDatabase(name) }
    private val database by connection
    private val changes =
        MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
            tryEmit(Unit)
        }
    private val transferChanges =
        MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
            tryEmit(Unit)
        }
    override val accounts: AccountCacheStore =
        object : AccountCacheStore {
            override suspend fun activate(sessionId: String?) {
                activateAccount(database.await<JsAny>(), sessionId).await<JsAny?>()
                changes.tryEmit(Unit)
                transferChanges.tryEmit(Unit)
            }
        }
    override val inbox: InboxStore = IndexedDbInboxStore(changes) { database.await<JsAny>() }
    override val dashboard: DashboardStore =
        IndexedDbDashboardStore(changes) { database.await<JsAny>() }

    override val transfers: TransferStore =
        IndexedDbTransferStore(transferChanges) { database.await<JsAny>() }

    override fun close() {
        if (connection.isInitialized()) closeDatabase(database)
    }
}

private class IndexedDbInboxStore(
    private val changes: MutableSharedFlow<Unit>,
    private val database: suspend () -> JsAny,
) : InboxStore {
    override fun observe() =
        changes.map {
            Json.decodeFromString<List<InboxRecord>>(
                readInbox(database()).await<JsString>().toString()
            )
        }

    override suspend fun upsert(record: InboxRecord) {
        writeInbox(database(), Json.encodeToString(record)).await<JsAny?>()
        changes.tryEmit(Unit)
    }

    override suspend fun markRead(id: String) {
        markInboxRead(database(), id).await<JsAny?>()
        changes.tryEmit(Unit)
    }

    override suspend fun clear() {
        clearInbox(database()).await<JsAny?>()
        changes.tryEmit(Unit)
    }
}

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 4);
    request.onupgradeneeded = event => {
        const db = request.result;
        if (event.oldVersion < 4) {
            db.createObjectStore('transfers', {keyPath: 'id'});
            db.createObjectStore('transfer_chunks', {keyPath: ['transferId', 'offset']});
        }
        if (event.oldVersion < 1) db.createObjectStore('inbox', {keyPath: 'id'});
        if (event.oldVersion < 3) db.createObjectStore('account_scope');
        if (event.oldVersion < 2) {
            db.createObjectStore('dashboard');
            db.createObjectStore('activity_preferences', {keyPath: 'activityId'});
            const outbox = db.createObjectStore('dashboard_outbox', {keyPath: 'sequence', autoIncrement: true});
            outbox.createIndex('operationId', 'operationId', {unique: true});
        }
    };
    request.onsuccess = () => { request.result.onversionchange = () => request.result.close(); resolve(request.result); };
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Database upgrade is blocked.'));
})"""
)
private external fun openAppDatabase(name: String): Promise<JsAny>

@JsFun(
    """(db) => new Promise((resolve, reject) => {
    const tx = db.transaction('inbox', 'readonly');
    const request = tx.objectStore('inbox').getAll();
    tx.oncomplete = () => resolve(JSON.stringify(request.result.sort((a,b) => b.createdAtEpochMillis - a.createdAtEpochMillis || a.id.localeCompare(b.id))));
    tx.onabort = () => reject(tx.error || new Error('Database read failed.'));
})"""
)
private external fun readInbox(database: JsAny): Promise<JsString>

@JsFun(
    """(db, json) => new Promise((resolve, reject) => {
    const tx = db.transaction('inbox', 'readwrite'); tx.objectStore('inbox').put(JSON.parse(json));
    tx.oncomplete = () => resolve(null); tx.onabort = () => reject(tx.error || new Error('Database write failed.'));
})"""
)
private external fun writeInbox(database: JsAny, json: String): Promise<JsAny?>

@JsFun(
    """(db, id) => new Promise((resolve, reject) => {
    const tx = db.transaction('inbox', 'readwrite'); const store = tx.objectStore('inbox');
    const request = store.get(id); request.onsuccess = () => { if (request.result) store.put({...request.result, read: true}); };
    tx.oncomplete = () => resolve(null); tx.onabort = () => reject(tx.error || new Error('Database update failed.'));
})"""
)
private external fun markInboxRead(database: JsAny, id: String): Promise<JsAny?>

@JsFun(
    """(db) => new Promise((resolve, reject) => {
    const tx = db.transaction('inbox', 'readwrite'); tx.objectStore('inbox').clear();
    tx.oncomplete = () => resolve(null); tx.onabort = () => reject(tx.error || new Error('Database clear failed.'));
})"""
)
private external fun clearInbox(database: JsAny): Promise<JsAny?>

@JsFun("(pending) => { pending.then(db => db.close(), () => {}); }")
private external fun closeDatabase(database: Promise<JsAny>)

@JsFun(
    """(db, scope) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'dashboard', 'activity_preferences', 'dashboard_outbox', 'inbox', 'transfers', 'transfer_chunks'], 'readwrite');
    const scopes = tx.objectStore('account_scope'), current = scopes.get('current');
    current.onsuccess = () => {
        if (current.result === scope) return;
        for (const name of ['dashboard', 'activity_preferences', 'dashboard_outbox', 'inbox', 'transfers', 'transfer_chunks']) tx.objectStore(name).clear();
        scopes.put(scope, 'current');
    };
    tx.oncomplete = () => resolve(null);
    tx.onabort = () => reject(tx.error || new Error('Account cache activation failed.'));
})"""
)
private external fun activateAccount(database: JsAny, scope: String?): Promise<JsAny?>
