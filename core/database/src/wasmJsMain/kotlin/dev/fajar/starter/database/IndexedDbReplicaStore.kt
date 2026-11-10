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
    override suspend fun containsRecords(partitionPrefix: String): Boolean {
        require(partitionPrefix.length in 1..160 && partitionPrefix.all { it in '!'..'~' })
        return containsReplicaRecords(database(), partitionPrefix).await<JsBoolean>().toBoolean()
    }

    override suspend fun checkpoint(scope: ReplicaScope, partition: String): ReplicaCheckpoint? {
        validateReplicaKey(scope, partition)
        return Json.decodeFromString(
            replicaCheckpoint(database(), Json.encodeToString(scope), partition, false)
                .await<JsString>()
                .toString()
        )
    }

    override fun observeCheckpoint(scope: ReplicaScope, partition: String) =
        changes.map { checkpoint(scope, partition) }.distinctUntilChanged()

    override fun observePublishedCheckpoint(scope: ReplicaScope, partition: String) =
        changes
            .map {
                validateReplicaKey(scope, partition)
                Json.decodeFromString<ReplicaCheckpoint?>(
                    replicaCheckpoint(database(), Json.encodeToString(scope), partition, true)
                        .await<JsString>()
                        .toString()
                )
            }
            .distinctUntilChanged()

    override fun observePublishedCheckpoints(
        scope: ReplicaScope,
        partitionPrefix: String,
        afterPartition: String?,
        limit: Int,
    ) =
        changes
            .map {
                validateReplicaPartitionPage(scope, partitionPrefix, afterPartition, limit)
                Json.decodeFromString<List<ReplicaCheckpoint>>(
                    replicaPublishedCheckpoints(
                            database(),
                            Json.encodeToString(scope),
                            partitionPrefix,
                            afterPartition,
                            limit,
                        )
                        .await<JsString>()
                        .toString()
                )
            }
            .distinctUntilChanged()

    override suspend fun replace(
        scope: ReplicaScope,
        partition: String,
        expected: ReplicaCheckpoint?,
        snapshot: ReplicaSnapshot?,
        wake: WorkWakeRequest?,
    ): Boolean {
        val retained =
            snapshot?.copy(documents = snapshot.documents.map { it.copy(tags = it.tags.toSet()) })
        validateReplicaReplacement(scope, partition, expected, retained)
        validateWorkWakeRequest(wake)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val changed =
                replaceReplica(
                        db,
                        Json.encodeToString(scope),
                        partition,
                        Json.encodeToString(expected),
                        Json.encodeToString(retained),
                        Json.encodeToString(wake),
                    )
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override suspend fun begin(
        scope: ReplicaScope,
        partition: String,
        generation: String,
        checkpoint: String,
        expectedGeneration: String?,
    ): Boolean {
        validateReplicaKey(scope, partition)
        validateReplicaGeneration(generation)
        validateReplicaCheckpoint(checkpoint)
        val db = database()
        currentCoroutineContext().ensureActive()
        // An admitted IndexedDB transaction finishes atomically even if its caller is cancelled.
        return withContext(NonCancellable) {
            val changed =
                beginReplica(
                        db,
                        Json.encodeToString(scope),
                        partition,
                        generation,
                        checkpoint,
                        expectedGeneration,
                    )
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override suspend fun discardStaging(
        scope: ReplicaScope,
        partition: String,
        expected: ReplicaCheckpoint,
    ): Boolean {
        validateReplicaReplacement(scope, partition, expected, null)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val changed =
                discardStagedReplica(
                        db,
                        Json.encodeToString(scope),
                        partition,
                        Json.encodeToString(expected),
                    )
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override suspend fun workingDocuments(
        scope: ReplicaScope,
        partition: String,
        ids: List<String>,
    ): ReplicaPage {
        val retained = ids.toList()
        validateReplicaKey(scope, partition)
        validateReplicaDocumentIds(retained)
        return Json.decodeFromString(
            workingReplicaDocuments(
                    database(),
                    Json.encodeToString(scope),
                    partition,
                    Json.encodeToString(retained),
                )
                .await<JsString>()
                .toString()
        )
    }

    override suspend fun commit(
        handle: ReplicaHandle,
        expectedRevision: Long,
        checkpoint: String,
        documents: List<ReplicaDocument>,
        publish: Boolean,
        wake: WorkWakeRequest?,
    ): Boolean {
        validateReplicaKey(handle.scope, handle.partition)
        validateReplicaGeneration(handle.generation)
        validateReplicaCheckpoint(checkpoint)
        val batch = documents.map { it.copy(tags = it.tags.toSet()) }
        validateReplicaBatch(expectedRevision, batch)
        validateWorkWakeRequest(wake)
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
                        Json.encodeToString(wake),
                    )
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override fun observe(scope: ReplicaScope, partition: String, after: String?, limit: Int) =
        changes
            .map {
                validateReplicaKey(scope, partition)
                validateReplicaPage(after, limit)
                Json.decodeFromString<ReplicaPage>(
                    replicaPage(
                            database(),
                            Json.encodeToString(scope),
                            partition,
                            after,
                            limit,
                            null,
                        )
                        .await<JsString>()
                        .toString()
                )
            }
            .distinctUntilChanged()

    override fun observeDocument(scope: ReplicaScope, partition: String, id: String) =
        changes
            .map {
                validateReplicaKey(scope, partition)
                validateReplicaGeneration(id)
                Json.decodeFromString<ReplicaPage>(
                    replicaPage(database(), Json.encodeToString(scope), partition, null, 1, id)
                        .await<JsString>()
                        .toString()
                )
            }
            .distinctUntilChanged()

    override fun observeQueries(
        scope: ReplicaScope,
        partition: String,
        queries: List<ReplicaQuery>,
    ) =
        queries.toList().let { retained ->
            changes
                .map {
                    validateReplicaKey(scope, partition)
                    validateReplicaQueries(retained)
                    Json.decodeFromString<ReplicaQueryPage>(
                        replicaQueryPages(
                                database(),
                                Json.encodeToString(scope),
                                partition,
                                Json.encodeToString(retained),
                            )
                            .await<JsString>()
                            .toString()
                    )
                }
                .distinctUntilChanged()
        }

    override suspend fun document(
        scope: ReplicaScope,
        partition: String,
        id: String,
    ): ReplicaDocument? {
        validateReplicaKey(scope, partition)
        validateReplicaGeneration(id)
        return Json.decodeFromString(
            replicaDocument(database(), Json.encodeToString(scope), partition, id)
                .await<JsString>()
                .toString()
        )
    }

    override suspend fun clear(scope: ReplicaScope, partition: String): Boolean {
        validateReplicaKey(scope, partition)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val changed =
                clearReplica(db, Json.encodeToString(scope), partition)
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }
}

@JsFun(
    """(db, prefix) => new Promise((resolve, reject) => {
    const tx = db.transaction('replica_generations', 'readonly');
    let found = false;
    const rows = tx.objectStore('replica_generations').openKeyCursor();
    rows.onsuccess = () => {
        const row = rows.result;
        if (!row) return;
        if (row.key[1].startsWith(prefix)) found = true;
        else row.continue();
    };
    tx.oncomplete = () => resolve(found);
    tx.onabort = () => reject(tx.error || new Error('Replica existence read failed.'));
})"""
)
private external fun containsReplicaRecords(db: JsAny, prefix: String): Promise<JsBoolean>

@JsFun(
    """(db, scopeJson, partition, publishedOnly) => new Promise((resolve, reject) => {
    const scope = JSON.parse(scopeJson), {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations'], 'readonly');
    let value = null;
    const owner = tx.objectStore('account_scope').get('current');
    const account = tx.objectStore('account_scope').get('command-account');
    account.onsuccess = () => {
        if (owner.result !== sessionId || (accountKey != null && account.result !== accountKey)) return;
        const head = tx.objectStore('replica_heads').get([ownerKey, partition]);
        head.onsuccess = () => {
            if (!head.result) return;
            const generation = publishedOnly ? head.result.activeGeneration : head.result.stagingGeneration ?? head.result.activeGeneration;
            if (generation == null) return;
            const row = tx.objectStore('replica_generations').get([ownerKey, partition, generation]);
            row.onsuccess = () => {
                if (row.result) value = {handle:{scope, partition, generation}, revision:row.result.revision, payload:row.result.checkpoint, published:head.result.activeGeneration === generation};
            };
        };
    };
    tx.oncomplete = () => resolve(JSON.stringify(value));
    tx.onabort = () => reject(tx.error || new Error('Replica checkpoint read failed.'));
})"""
)
private external fun replicaCheckpoint(
    db: JsAny,
    scope: String,
    partition: String,
    publishedOnly: Boolean,
): Promise<JsString>

@JsFun(
    """(db, scopeJson, prefix, after, limit) => new Promise((resolve, reject) => {
    const scope = JSON.parse(scopeJson), {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations'], 'readonly');
    const values = [];
    const owner = tx.objectStore('account_scope').get('current');
    const account = tx.objectStore('account_scope').get('command-account');
    account.onsuccess = () => {
        if (owner.result !== sessionId || (accountKey != null && account.result !== accountKey)) return;
        const range = IDBKeyRange.bound([ownerKey, after ?? prefix], [ownerKey, prefix + '\uffff'], after != null);
        const rows = tx.objectStore('replica_heads').openCursor(range);
        rows.onsuccess = () => {
            const cursor = rows.result;
            if (!cursor || values.length === limit) return;
            const {partition, activeGeneration: generation} = cursor.value;
            if (generation == null) { cursor.continue(); return; }
            const row = tx.objectStore('replica_generations').get([ownerKey, partition, generation]);
            row.onsuccess = () => {
                if (row.result) values.push({handle:{scope, partition, generation}, revision:row.result.revision, payload:row.result.checkpoint, published:true});
                if (values.length < limit) cursor.continue();
            };
        };
    };
    tx.oncomplete = () => resolve(JSON.stringify(values));
    tx.onabort = () => reject(tx.error || new Error('Replica checkpoint page read failed.'));
})"""
)
private external fun replicaPublishedCheckpoints(
    db: JsAny,
    scope: String,
    prefix: String,
    after: String?,
    limit: Int,
): Promise<JsString>

@JsFun(
    """(db, scopeJson, partition, expectedJson, snapshotJson, wakeJson) => new Promise((resolve, reject) => {
    const scope = JSON.parse(scopeJson), {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const expected = JSON.parse(expectedJson), snapshot = JSON.parse(snapshotJson), wake = JSON.parse(wakeJson);
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents', ...(wake == null ? [] : ['work_wakes'])], 'readwrite');
    const heads = tx.objectStore('replica_heads'), generations = tx.objectStore('replica_generations'), documents = tx.objectStore('replica_documents');
    let changed = false;
    const replace = current => {
        if (current == null ? expected != null :
            expected == null || expected.handle.scope.sessionId !== sessionId || (expected.handle.scope.accountKey ?? null) !== accountKey ||
            expected.handle.partition !== partition || expected.handle.generation !== current.generation ||
            expected.revision !== current.revision || expected.payload !== current.checkpoint ||
            expected.published !== current.published) return;
        const write = () => {
            if (wake != null) tx.objectStore('work_wakes').put({key:wake.key, generation:wake.generation, pending:true});
            heads.delete([ownerKey, partition]);
            generations.delete(IDBKeyRange.bound([ownerKey, partition, ''], [ownerKey, partition, '\uffff']));
            documents.delete(IDBKeyRange.bound([ownerKey, partition, '', ''], [ownerKey, partition, '\uffff', '\uffff']));
            if (snapshot != null) {
                const {generation, checkpoint} = snapshot;
                generations.put({ownerKey, partition, generation, revision:0, checkpoint});
                for (const item of snapshot.documents) {
                    documents.put({...item, ownerKey, partition, generation, visibleSort:item.payload == null ? undefined : item.sortKey,
                        tagKeys:item.payload == null ? [] : (item.tags ?? []).map(tag => [ownerKey, partition, generation, tag, item.sortKey])});
                }
                heads.put({ownerKey, partition, activeGeneration:generation, stagingGeneration:null});
            }
            changed = true;
        };
        const admitWake = () => {
            if (wake == null) { write(); return; }
            const store = tx.objectStore('work_wakes'), current = store.get(wake.key);
            current.onsuccess = () => {
                if (current.result?.generation === wake.generation) return;
                if (current.result) { write(); return; }
                const count = store.count();
                count.onsuccess = () => { if (count.result < wake.maxKeys) write(); };
            };
        };
        if (snapshot == null) admitWake();
        else {
            const existing = generations.get([ownerKey, partition, snapshot.generation]);
            existing.onsuccess = () => { if (!existing.result) admitWake(); };
        }
    };
    const owner = tx.objectStore('account_scope').get('current');
    const account = tx.objectStore('account_scope').get('command-account');
    account.onsuccess = () => {
        if (owner.result !== sessionId || (accountKey != null && account.result !== accountKey)) return;
        const head = heads.get([ownerKey, partition]);
        head.onsuccess = () => {
            const generation = head.result?.stagingGeneration ?? head.result?.activeGeneration;
            if (generation == null) { replace(null); return; }
            const row = generations.get([ownerKey, partition, generation]);
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
    scope: String,
    partition: String,
    expected: String,
    snapshot: String,
    wake: String,
): Promise<JsBoolean>

@JsFun(
    """(db, scopeJson, partition, generation, checkpoint, expected) => new Promise((resolve, reject) => {
    const scope = JSON.parse(scopeJson), {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readwrite');
    const heads = tx.objectStore('replica_heads'), generations = tx.objectStore('replica_generations'), documents = tx.objectStore('replica_documents');
    let changed = false;
    const owner = tx.objectStore('account_scope').get('current');
    const account = tx.objectStore('account_scope').get('command-account');
    account.onsuccess = () => {
        if (owner.result !== sessionId || (accountKey != null && account.result !== accountKey)) return;
        const head = heads.get([ownerKey, partition]);
        head.onsuccess = () => {
            const previous = head.result;
            if ((previous?.stagingGeneration ?? previous?.activeGeneration ?? null) !== expected) return;
            const exists = generations.get([ownerKey, partition, generation]);
            exists.onsuccess = () => {
                if (exists.result) return;
                if (previous?.stagingGeneration != null) {
                    generations.delete([ownerKey, partition, previous.stagingGeneration]);
                    documents.delete(IDBKeyRange.bound([ownerKey, partition, previous.stagingGeneration, ''], [ownerKey, partition, previous.stagingGeneration, '\uffff']));
                }
                generations.put({ownerKey, partition, generation, revision:0, checkpoint});
                heads.put({ownerKey, partition, activeGeneration:previous?.activeGeneration ?? null, stagingGeneration:generation});
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
    scope: String,
    partition: String,
    generation: String,
    checkpoint: String,
    expected: String?,
): Promise<JsBoolean>

@JsFun(
    """(db, handleJson, expected, checkpoint, documentsJson, publish, wakeJson) => new Promise((resolve, reject) => {
    const handle = JSON.parse(handleJson), incoming = JSON.parse(documentsJson), wake = JSON.parse(wakeJson);
    const {scope, partition, generation} = handle;
    const {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents', ...(wake == null ? [] : ['work_wakes'])], 'readwrite');
    const heads = tx.objectStore('replica_heads'), generations = tx.objectStore('replica_generations'), documents = tx.objectStore('replica_documents');
    let changed = false;
    const owner = tx.objectStore('account_scope').get('current');
    const account = tx.objectStore('account_scope').get('command-account');
    account.onsuccess = () => {
        if (owner.result !== sessionId || (accountKey != null && account.result !== accountKey)) return;
        const head = heads.get([ownerKey, partition]);
        head.onsuccess = () => {
            if ((head.result?.stagingGeneration ?? head.result?.activeGeneration) !== generation) return;
            const current = generations.get([ownerKey, partition, generation]);
            current.onsuccess = () => {
                if (!current.result || current.result.revision !== expected) return;
                const writeNext = index => {
                    if (index === incoming.length) {
                        generations.put({...current.result, revision:expected + 1, checkpoint});
                        if (publish) {
                            const old = head.result.activeGeneration;
                            if (old != null && old !== generation) {
                                generations.delete([ownerKey, partition, old]);
                                documents.delete(IDBKeyRange.bound([ownerKey, partition, old, ''], [ownerKey, partition, old, '\uffff']));
                            }
                            heads.put({ownerKey, partition, activeGeneration:generation, stagingGeneration:null});
                        }
                        changed = true;
                        return;
                    }
                    const item = incoming[index], previous = documents.get([ownerKey, partition, generation, item.id]);
                    previous.onsuccess = () => {
                        const old = previous.result;
                        if (!old || (old.version <= item.version && !(old.version === item.version && old.payload == null && item.payload != null))) {
                            documents.put({...item, ownerKey, partition, generation, visibleSort:item.payload == null ? undefined : item.sortKey,
                                tagKeys:item.payload == null ? [] : (item.tags ?? []).map(tag => [ownerKey, partition, generation, tag, item.sortKey])});
                        }
                        writeNext(index + 1);
                    };
                };
                const write = () => {
                    if (wake != null) tx.objectStore('work_wakes').put({key:wake.key, generation:wake.generation, pending:true});
                    writeNext(0);
                };
                if (wake == null) { write(); return; }
                const store = tx.objectStore('work_wakes'), currentWake = store.get(wake.key);
                currentWake.onsuccess = () => {
                    if (currentWake.result?.generation === wake.generation) return;
                    if (currentWake.result) { write(); return; }
                    const count = store.count();
                    count.onsuccess = () => { if (count.result < wake.maxKeys) write(); };
                };
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
    wake: String,
): Promise<JsBoolean>

@JsFun(
    """(db, scopeJson, partition, after, limit, documentId) => new Promise((resolve, reject) => {
    const scope = JSON.parse(scopeJson), {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readonly');
    const value = {checkpoint:null, documents:[]};
    const owner = tx.objectStore('account_scope').get('current');
    const account = tx.objectStore('account_scope').get('command-account');
    account.onsuccess = () => {
        if (owner.result !== sessionId || (accountKey != null && account.result !== accountKey)) return;
        const head = tx.objectStore('replica_heads').get([ownerKey, partition]);
        head.onsuccess = () => {
            const generation = head.result?.activeGeneration;
            if (generation == null) return;
            const row = tx.objectStore('replica_generations').get([ownerKey, partition, generation]);
            row.onsuccess = () => {
                if (!row.result) return;
                value.checkpoint = {handle:{scope, partition, generation}, revision:row.result.revision, payload:row.result.checkpoint, published:true};
                if (documentId != null) {
                    const item = tx.objectStore('replica_documents').get([ownerKey, partition, generation, documentId]);
                    item.onsuccess = () => {
                        if (item.result?.payload != null) {
                            const {id, version, sortKey, payload, tags = []} = item.result;
                            value.documents = [{id, version, sortKey, payload, tags}];
                        }
                    };
                    return;
                }
                const range = IDBKeyRange.bound([ownerKey, partition, generation, after ?? ''], [ownerKey, partition, generation, '\uffff'], after != null);
                const items = tx.objectStore('replica_documents').index('visible').getAll(range, limit);
                items.onsuccess = () => { value.documents = items.result.map(({id, version, sortKey, payload, tags = []}) => ({id, version, sortKey, payload, tags})); };
            };
        };
    };
    tx.oncomplete = () => resolve(JSON.stringify(value));
    tx.onabort = () => reject(tx.error || new Error('Replica page read failed.'));
})"""
)
private external fun replicaPage(
    db: JsAny,
    scope: String,
    partition: String,
    after: String?,
    limit: Int,
    documentId: String?,
): Promise<JsString>

@JsFun(
    """(db, scopeJson, partition, id) => new Promise((resolve, reject) => {
    const scope = JSON.parse(scopeJson), {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_documents'], 'readonly');
    let value = null;
    const owner = tx.objectStore('account_scope').get('current');
    const account = tx.objectStore('account_scope').get('command-account');
    account.onsuccess = () => {
        if (owner.result !== sessionId || (accountKey != null && account.result !== accountKey)) return;
        const head = tx.objectStore('replica_heads').get([ownerKey, partition]);
        head.onsuccess = () => {
            const generation = head.result?.activeGeneration;
            if (generation == null) return;
            const row = tx.objectStore('replica_documents').get([ownerKey, partition, generation, id]);
            row.onsuccess = () => {
                if (row.result?.payload != null) {
                    const {id, version, sortKey, payload, tags = []} = row.result; value = {id, version, sortKey, payload, tags};
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
    scope: String,
    partition: String,
    id: String,
): Promise<JsString>

@JsFun(
    """(db, scopeJson, partition) => new Promise((resolve, reject) => {
    const scope = JSON.parse(scopeJson), {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readwrite');
    let changed = false;
    const owner = tx.objectStore('account_scope').get('current');
    const account = tx.objectStore('account_scope').get('command-account');
    account.onsuccess = () => {
        if (owner.result !== sessionId || (accountKey != null && account.result !== accountKey)) return;
        tx.objectStore('replica_heads').delete([ownerKey, partition]);
        tx.objectStore('replica_generations').delete(IDBKeyRange.bound([ownerKey, partition, ''], [ownerKey, partition, '\uffff']));
        tx.objectStore('replica_documents').delete(IDBKeyRange.bound([ownerKey, partition, '', ''], [ownerKey, partition, '\uffff', '\uffff']));
        changed = true;
    };
    tx.oncomplete = () => resolve(changed);
    tx.onabort = () => reject(tx.error || new Error('Replica removal failed.'));
})"""
)
private external fun clearReplica(db: JsAny, scope: String, partition: String): Promise<JsBoolean>

@JsFun(
    """(db, scopeJson, partition, expectedJson) => new Promise((resolve, reject) => {
    const scope = JSON.parse(scopeJson), expected = JSON.parse(expectedJson);
    const {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readwrite');
    const heads = tx.objectStore('replica_heads'), generations = tx.objectStore('replica_generations');
    let changed = false;
    const owner = tx.objectStore('account_scope').get('current');
    const account = tx.objectStore('account_scope').get('command-account');
    account.onsuccess = () => {
        if (expected.published || owner.result !== sessionId || (accountKey != null && account.result !== accountKey)) return;
        const head = heads.get([ownerKey, partition]);
        head.onsuccess = () => {
            const current = head.result, generation = expected.handle.generation;
            if (current?.stagingGeneration !== generation) return;
            const row = generations.get([ownerKey, partition, generation]);
            row.onsuccess = () => {
                if (!row.result || row.result.revision !== expected.revision || row.result.checkpoint !== expected.payload) return;
                generations.delete([ownerKey, partition, generation]);
                tx.objectStore('replica_documents').delete(IDBKeyRange.bound([ownerKey, partition, generation, ''], [ownerKey, partition, generation, '\uffff']));
                if (current.activeGeneration == null) heads.delete([ownerKey, partition]);
                else heads.put({...current, stagingGeneration:null});
                changed = true;
            };
        };
    };
    tx.oncomplete = () => resolve(changed);
    tx.onabort = () => reject(tx.error || new Error('Staged replica discard failed.'));
})"""
)
private external fun discardStagedReplica(
    db: JsAny,
    scope: String,
    partition: String,
    expected: String,
): Promise<JsBoolean>

@JsFun(
    """(db, scopeJson, partition, idsJson) => new Promise((resolve, reject) => {
    const scope = JSON.parse(scopeJson), ids = JSON.parse(idsJson);
    const {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readonly');
    const value = {checkpoint:null, documents:[]};
    const owner = tx.objectStore('account_scope').get('current');
    const account = tx.objectStore('account_scope').get('command-account');
    account.onsuccess = () => {
        if (owner.result !== sessionId || (accountKey != null && account.result !== accountKey)) return;
        const head = tx.objectStore('replica_heads').get([ownerKey, partition]);
        head.onsuccess = () => {
            const current = head.result, generation = current?.stagingGeneration ?? current?.activeGeneration;
            if (generation == null) return;
            const row = tx.objectStore('replica_generations').get([ownerKey, partition, generation]);
            row.onsuccess = () => {
                if (!row.result) return;
                value.checkpoint = {handle:{scope, partition, generation}, revision:row.result.revision,
                    payload:row.result.checkpoint, published:current.activeGeneration === generation};
                for (const id of ids) {
                    const record = tx.objectStore('replica_documents').get([ownerKey, partition, generation, id]);
                    record.onsuccess = () => {
                        if (!record.result) return;
                        const {id, version, sortKey, payload = null, tags = []} = record.result;
                        value.documents.push({id, version, sortKey, payload, tags});
                    };
                }
            };
        };
    };
    tx.oncomplete = () => {
        value.documents.sort((a,b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
        resolve(JSON.stringify(value));
    };
    tx.onabort = () => reject(tx.error || new Error('Working replica read failed.'));
})"""
)
private external fun workingReplicaDocuments(
    db: JsAny,
    scope: String,
    partition: String,
    ids: String,
): Promise<JsString>
