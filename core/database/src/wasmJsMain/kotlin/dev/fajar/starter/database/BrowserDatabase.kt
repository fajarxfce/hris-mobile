@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
    private val replicaChanges =
        MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
            tryEmit(Unit)
        }
    private val commandChanges =
        MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
            tryEmit(Unit)
        }
    private val blobChanges =
        MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
            tryEmit(Unit)
        }
    override val accounts: AccountCacheStore =
        object : AccountCacheStore {
            override suspend fun activate(sessionId: String?, accountKey: String?) {
                require(accountKey == null || (sessionId != null && accountKey.length in 1..2048))
                val db = database.await<JsAny>()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    activateAccount(db, sessionId, accountKey).await<JsAny?>()
                    changes.tryEmit(Unit)
                    transferChanges.tryEmit(Unit)
                    replicaChanges.tryEmit(Unit)
                    commandChanges.tryEmit(Unit)
                    blobChanges.tryEmit(Unit)
                }
            }
        }
    override val inbox: InboxStore = IndexedDbInboxStore(changes) { database.await<JsAny>() }
    override val dashboard: DashboardStore =
        IndexedDbDashboardStore(changes) { database.await<JsAny>() }

    override val transfers: TransferStore =
        IndexedDbTransferStore(transferChanges) { database.await<JsAny>() }
    override val replicas: ReplicaStore =
        IndexedDbReplicaStore(replicaChanges) { database.await<JsAny>() }
    override val commands: CommandStore =
        IndexedDbCommandStore(commandChanges) { database.await<JsAny>() }
    override val blobs: BlobStore = IndexedDbBlobStore(blobChanges) { database.await<JsAny>() }

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
    const request = indexedDB.open(name, 8);
    let settled = false;
    request.onupgradeneeded = event => {
        const db = request.result;
        if (event.oldVersion < 7) {
            db.createObjectStore('durable_blobs', {keyPath:['accountKey', 'partition', 'id']});
            db.createObjectStore('durable_blob_chunks', {keyPath:['accountKey', 'partition', 'id', 'offset']});
        }
        if (event.oldVersion < 6) {
            const commands = db.createObjectStore('durable_commands', {keyPath:['accountKey', 'partition', 'id']});
            commands.createIndex('account', 'accountKey');
            commands.createIndex('resource', ['accountKey', 'partition', 'resourceId'], {unique:true});
            commands.createIndex('ordered', ['accountKey', 'partition', 'sortKey'], {unique:true});
        }
        if (event.oldVersion < 8) {
            for (const [name, keyPath] of [
                ['replica_heads', ['ownerKey', 'partition']],
                ['replica_generations', ['ownerKey', 'partition', 'generation']],
                ['replica_documents', ['ownerKey', 'partition', 'generation', 'id']]
            ]) {
                const previous = db.objectStoreNames.contains(name) ? request.transaction.objectStore(name) : null;
                if (previous) previous.name = name + '_session';
                const store = db.createObjectStore(name, {keyPath});
                if (name === 'replica_documents') {
                    store.createIndex('ordered', ['ownerKey', 'partition', 'generation', 'sortKey'], {unique:true});
                    store.createIndex('visible', ['ownerKey', 'partition', 'generation', 'visibleSort']);
                }
                if (previous) {
                    const rows = previous.openCursor();
                    rows.onsuccess = () => {
                        const row = rows.result;
                        if (!row) { db.deleteObjectStore(previous.name); return; }
                        const {sessionId, ...record} = row.value;
                        store.put({...record, ownerKey:''});
                        row.continue();
                    };
                }
            }
        }
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
    request.onsuccess = () => {
        if (settled) { request.result.close(); return; }
        settled = true; request.result.onversionchange = () => request.result.close(); resolve(request.result);
    };
    request.onerror = () => { settled = true; reject(request.error); };
    request.onblocked = () => { settled = true; reject(new Error('Database upgrade is blocked.')); };
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
    """(db, scope, account) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'dashboard', 'activity_preferences', 'dashboard_outbox', 'inbox', 'transfers', 'transfer_chunks', 'replica_heads', 'replica_generations', 'replica_documents'], 'readwrite');
    const scopes = tx.objectStore('account_scope'), current = scopes.get('current'), principal = scopes.get('command-account');
    principal.onsuccess = () => {
        if (current.result === scope && (principal.result ?? null) === account) return;
        for (const name of ['dashboard', 'activity_preferences', 'dashboard_outbox', 'inbox', 'transfers', 'transfer_chunks']) tx.objectStore(name).clear();
        tx.objectStore('replica_heads').delete(IDBKeyRange.bound(['', ''], ['', '\uffff']));
        tx.objectStore('replica_generations').delete(IDBKeyRange.bound(['', '', ''], ['', '\uffff', '\uffff']));
        tx.objectStore('replica_documents').delete(IDBKeyRange.bound(['', '', '', ''], ['', '\uffff', '\uffff', '\uffff']));
        scopes.put(scope, 'current');
        scopes.put(account, 'command-account');
    };
    tx.oncomplete = () => resolve(null);
    tx.onabort = () => reject(tx.error || new Error('Account cache activation failed.'));
})"""
)
private external fun activateAccount(
    database: JsAny,
    scope: String?,
    account: String?,
): Promise<JsAny?>
