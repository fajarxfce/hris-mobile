@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

internal class IndexedDbReplicaStore(
    private val changes: MutableSharedFlow<Unit>,
    private val database: suspend () -> JsAny,
) : ReplicaStore {
    override suspend fun checkpoint(sessionId: String, partition: String): ReplicaCheckpoint? {
        validateReplicaKey(sessionId, partition)
        return Json.decodeFromString(
            replicaCheckpoint(database(), sessionId, partition).await<JsString>().toString()
        )
    }

    override suspend fun replace(
        sessionId: String,
        partition: String,
        expected: ReplicaCheckpoint?,
        snapshot: ReplicaSnapshot?,
    ): Boolean {
        val retained = snapshot?.copy(documents = snapshot.documents.toList())
        validateReplicaReplacement(sessionId, partition, expected, retained)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val changed =
                replaceReplica(
                        db,
                        sessionId,
                        partition,
                        Json.encodeToString(expected),
                        Json.encodeToString(retained),
                    )
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override suspend fun begin(
        sessionId: String,
        partition: String,
        generation: String,
        checkpoint: String,
        expectedGeneration: String?,
    ): Boolean {
        validateReplicaKey(sessionId, partition)
        validateReplicaGeneration(generation)
        validateReplicaCheckpoint(checkpoint)
        val db = database()
        currentCoroutineContext().ensureActive()
        // An admitted IndexedDB transaction finishes atomically even if its caller is cancelled.
        return withContext(NonCancellable) {
            val changed =
                beginReplica(db, sessionId, partition, generation, checkpoint, expectedGeneration)
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override suspend fun commit(
        handle: ReplicaHandle,
        expectedRevision: Long,
        checkpoint: String,
        documents: List<ReplicaDocument>,
        publish: Boolean,
    ): Boolean {
        validateReplicaKey(handle.sessionId, handle.partition)
        validateReplicaGeneration(handle.generation)
        validateReplicaCheckpoint(checkpoint)
        val batch = documents.toList()
        validateReplicaBatch(expectedRevision, batch)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val changed =
                commitReplica(
                        db,
                        Json.encodeToString(handle),
                        expectedRevision.toDouble(),
                        checkpoint,
                        Json.encodeToString(batch),
                        publish,
                    )
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override fun observe(sessionId: String, partition: String, after: String?, limit: Int) =
        changes
            .map {
                validateReplicaKey(sessionId, partition)
                validateReplicaPage(after, limit)
                Json.decodeFromString<ReplicaPage>(
                    replicaPage(database(), sessionId, partition, after, limit, null)
                        .await<JsString>()
                        .toString()
                )
            }
            .distinctUntilChanged()

    override fun observeDocument(sessionId: String, partition: String, id: String) =
        changes
            .map {
                validateReplicaKey(sessionId, partition)
                validateReplicaGeneration(id)
                Json.decodeFromString<ReplicaPage>(
                    replicaPage(database(), sessionId, partition, null, 1, id)
                        .await<JsString>()
                        .toString()
                )
            }
            .distinctUntilChanged()

    override suspend fun document(
        sessionId: String,
        partition: String,
        id: String,
    ): ReplicaDocument? {
        validateReplicaKey(sessionId, partition)
        validateReplicaGeneration(id)
        return Json.decodeFromString(
            replicaDocument(database(), sessionId, partition, id).await<JsString>().toString()
        )
    }

    override suspend fun clear(sessionId: String, partition: String): Boolean {
        validateReplicaKey(sessionId, partition)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val changed = clearReplica(db, sessionId, partition).await<JsBoolean>().toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }
}

@JsFun(
    """(db, sessionId, partition) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations'], 'readonly');
    let value = null;
    const owner = tx.objectStore('account_scope').get('current');
    owner.onsuccess = () => {
        if (owner.result !== sessionId) return;
        const head = tx.objectStore('replica_heads').get(partition);
        head.onsuccess = () => {
            if (!head.result) return;
            const generation = head.result.stagingGeneration ?? head.result.activeGeneration;
            if (generation == null) return;
            const row = tx.objectStore('replica_generations').get([partition, generation]);
            row.onsuccess = () => {
                if (row.result) value = {handle:{sessionId, partition, generation}, revision:row.result.revision, payload:row.result.checkpoint, published:head.result.activeGeneration === generation};
            };
        };
    };
    tx.oncomplete = () => resolve(JSON.stringify(value));
    tx.onabort = () => reject(tx.error || new Error('Replica checkpoint read failed.'));
})"""
)
private external fun replicaCheckpoint(
    db: JsAny,
    sessionId: String,
    partition: String,
): Promise<JsString>

@JsFun(
    """(db, sessionId, partition, expectedJson, snapshotJson) => new Promise((resolve, reject) => {
    const expected = JSON.parse(expectedJson), snapshot = JSON.parse(snapshotJson);
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readwrite');
    const heads = tx.objectStore('replica_heads'), generations = tx.objectStore('replica_generations'), documents = tx.objectStore('replica_documents');
    let changed = false;
    const replace = current => {
        if (current == null ? expected != null :
            expected == null || expected.handle.sessionId !== sessionId ||
            expected.handle.partition !== partition || expected.handle.generation !== current.generation ||
            expected.revision !== current.revision || expected.payload !== current.checkpoint ||
            expected.published !== current.published) return;
        const write = () => {
            heads.delete(partition);
            generations.delete(IDBKeyRange.bound([partition, ''], [partition, '\uffff']));
            documents.delete(IDBKeyRange.bound([partition, '', ''], [partition, '\uffff', '\uffff']));
            if (snapshot != null) {
                const {generation, checkpoint} = snapshot;
                generations.put({partition, generation, sessionId, revision:0, checkpoint});
                for (const item of snapshot.documents) {
                    documents.put({...item, partition, generation, visibleSort:item.payload == null ? undefined : item.sortKey});
                }
                heads.put({partition, activeGeneration:generation, stagingGeneration:null});
            }
            changed = true;
        };
        if (snapshot == null) write();
        else {
            const existing = generations.get([partition, snapshot.generation]);
            existing.onsuccess = () => { if (!existing.result) write(); };
        }
    };
    const owner = tx.objectStore('account_scope').get('current');
    owner.onsuccess = () => {
        if (owner.result !== sessionId) return;
        const head = heads.get(partition);
        head.onsuccess = () => {
            const generation = head.result?.stagingGeneration ?? head.result?.activeGeneration;
            if (generation == null) { replace(null); return; }
            const row = generations.get([partition, generation]);
            row.onsuccess = () => {
                if (row.result) replace({...row.result, published:head.result.activeGeneration === generation});
            };
        };
    };
    tx.oncomplete = () => resolve(changed);
    tx.onabort = () => reject(tx.error || new Error('Replica replacement failed.'));
})"""
)
private external fun replaceReplica(
    db: JsAny,
    sessionId: String,
    partition: String,
    expected: String,
    snapshot: String,
): Promise<JsBoolean>

@JsFun(
    """(db, sessionId, partition, generation, checkpoint, expected) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readwrite');
    const heads = tx.objectStore('replica_heads'), generations = tx.objectStore('replica_generations'), documents = tx.objectStore('replica_documents');
    let changed = false;
    const owner = tx.objectStore('account_scope').get('current');
    owner.onsuccess = () => {
        if (owner.result !== sessionId) return;
        const head = heads.get(partition);
        head.onsuccess = () => {
            const previous = head.result;
            if ((previous?.stagingGeneration ?? previous?.activeGeneration ?? null) !== expected) return;
            const exists = generations.get([partition, generation]);
            exists.onsuccess = () => {
                if (exists.result) return;
                if (previous?.stagingGeneration != null) {
                    generations.delete([partition, previous.stagingGeneration]);
                    documents.delete(IDBKeyRange.bound([partition, previous.stagingGeneration, ''], [partition, previous.stagingGeneration, '\uffff']));
                }
                generations.put({partition, generation, sessionId, revision:0, checkpoint});
                heads.put({partition, activeGeneration:previous?.activeGeneration ?? null, stagingGeneration:generation});
                changed = true;
            };
        };
    };
    tx.oncomplete = () => resolve(changed);
    tx.onabort = () => reject(tx.error || new Error('Replica creation failed.'));
})"""
)
private external fun beginReplica(
    db: JsAny,
    sessionId: String,
    partition: String,
    generation: String,
    checkpoint: String,
    expected: String?,
): Promise<JsBoolean>

@JsFun(
    """(db, handleJson, expected, checkpoint, documentsJson, publish) => new Promise((resolve, reject) => {
    const handle = JSON.parse(handleJson), incoming = JSON.parse(documentsJson);
    const {sessionId, partition, generation} = handle;
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readwrite');
    const heads = tx.objectStore('replica_heads'), generations = tx.objectStore('replica_generations'), documents = tx.objectStore('replica_documents');
    let changed = false;
    const owner = tx.objectStore('account_scope').get('current');
    owner.onsuccess = () => {
        if (owner.result !== sessionId) return;
        const head = heads.get(partition);
        head.onsuccess = () => {
            if ((head.result?.stagingGeneration ?? head.result?.activeGeneration) !== generation) return;
            const current = generations.get([partition, generation]);
            current.onsuccess = () => {
                if (!current.result || current.result.sessionId !== sessionId || current.result.revision !== expected) return;
                const writeNext = index => {
                    if (index === incoming.length) {
                        generations.put({...current.result, revision:expected + 1, checkpoint});
                        if (publish) {
                            const old = head.result.activeGeneration;
                            if (old != null && old !== generation) {
                                generations.delete([partition, old]);
                                documents.delete(IDBKeyRange.bound([partition, old, ''], [partition, old, '\uffff']));
                            }
                            heads.put({partition, activeGeneration:generation, stagingGeneration:null});
                        }
                        changed = true;
                        return;
                    }
                    const item = incoming[index], previous = documents.get([partition, generation, item.id]);
                    previous.onsuccess = () => {
                        const old = previous.result;
                        if (!old || (old.version <= item.version && !(old.version === item.version && old.payload == null && item.payload != null))) {
                            documents.put({...item, partition, generation, visibleSort:item.payload == null ? undefined : item.sortKey});
                        }
                        writeNext(index + 1);
                    };
                };
                writeNext(0);
            };
        };
    };
    tx.oncomplete = () => resolve(changed);
    tx.onabort = () => reject(tx.error || new Error('Replica commit failed.'));
})"""
)
private external fun commitReplica(
    db: JsAny,
    handle: String,
    expected: Double,
    checkpoint: String,
    documents: String,
    publish: Boolean,
): Promise<JsBoolean>

@JsFun(
    """(db, sessionId, partition, after, limit, documentId) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readonly');
    const value = {checkpoint:null, documents:[]};
    const owner = tx.objectStore('account_scope').get('current');
    owner.onsuccess = () => {
        if (owner.result !== sessionId) return;
        const head = tx.objectStore('replica_heads').get(partition);
        head.onsuccess = () => {
            const generation = head.result?.activeGeneration;
            if (generation == null) return;
            const row = tx.objectStore('replica_generations').get([partition, generation]);
            row.onsuccess = () => {
                if (!row.result) return;
                value.checkpoint = {handle:{sessionId, partition, generation}, revision:row.result.revision, payload:row.result.checkpoint, published:true};
                if (documentId != null) {
                    const item = tx.objectStore('replica_documents').get([partition, generation, documentId]);
                    item.onsuccess = () => {
                        if (item.result?.payload != null) {
                            const {id, version, sortKey, payload} = item.result;
                            value.documents = [{id, version, sortKey, payload}];
                        }
                    };
                    return;
                }
                const range = IDBKeyRange.bound([partition, generation, after ?? ''], [partition, generation, '\uffff'], after != null);
                const items = tx.objectStore('replica_documents').index('visible').getAll(range, limit);
                items.onsuccess = () => { value.documents = items.result.map(({id, version, sortKey, payload}) => ({id, version, sortKey, payload})); };
            };
        };
    };
    tx.oncomplete = () => resolve(JSON.stringify(value));
    tx.onabort = () => reject(tx.error || new Error('Replica page read failed.'));
})"""
)
private external fun replicaPage(
    db: JsAny,
    sessionId: String,
    partition: String,
    after: String?,
    limit: Int,
    documentId: String?,
): Promise<JsString>

@JsFun(
    """(db, sessionId, partition, id) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_documents'], 'readonly');
    let value = null;
    const owner = tx.objectStore('account_scope').get('current');
    owner.onsuccess = () => {
        if (owner.result !== sessionId) return;
        const head = tx.objectStore('replica_heads').get(partition);
        head.onsuccess = () => {
            const generation = head.result?.activeGeneration;
            if (generation == null) return;
            const row = tx.objectStore('replica_documents').get([partition, generation, id]);
            row.onsuccess = () => {
                if (row.result?.payload != null) {
                    const {id, version, sortKey, payload} = row.result; value = {id, version, sortKey, payload};
                }
            };
        };
    };
    tx.oncomplete = () => resolve(JSON.stringify(value));
    tx.onabort = () => reject(tx.error || new Error('Replica document read failed.'));
})"""
)
private external fun replicaDocument(
    db: JsAny,
    sessionId: String,
    partition: String,
    id: String,
): Promise<JsString>

@JsFun(
    """(db, sessionId, partition) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readwrite');
    let changed = false;
    const owner = tx.objectStore('account_scope').get('current');
    owner.onsuccess = () => {
        if (owner.result !== sessionId) return;
        tx.objectStore('replica_heads').delete(partition);
        tx.objectStore('replica_generations').delete(IDBKeyRange.bound([partition, ''], [partition, '\uffff']));
        tx.objectStore('replica_documents').delete(IDBKeyRange.bound([partition, '', ''], [partition, '\uffff', '\uffff']));
        changed = true;
    };
    tx.oncomplete = () => resolve(changed);
    tx.onabort = () => reject(tx.error || new Error('Replica removal failed.'));
})"""
)
private external fun clearReplica(
    db: JsAny,
    sessionId: String,
    partition: String,
): Promise<JsBoolean>
