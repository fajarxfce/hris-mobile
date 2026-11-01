@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise

@JsFun(
    """(db, scopeJson, partition, queriesJson) => new Promise((resolve, reject) => {
    const scope = JSON.parse(scopeJson), queries = JSON.parse(queriesJson);
    const {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readonly');
    const value = {checkpoint:null, pages:queries.map(() => [])};
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
                queries.forEach(({tag = null, after = null, before = null, limit = 50}, index) => {
                    const prefix = tag == null ? [ownerKey, partition, generation] : [ownerKey, partition, generation, tag];
                    const range = before == null
                        ? IDBKeyRange.bound([...prefix, after ?? ''], [...prefix, '\uffff'], after != null)
                        : IDBKeyRange.bound([...prefix, ''], [...prefix, before], false, true);
                    if (before != null) {
                        const cursor = tx.objectStore('replica_documents').index(tag == null ? 'visible' : 'tagged').openCursor(range, 'prev');
                        cursor.onsuccess = () => {
                            const row = cursor.result;
                            if (!row) return;
                            const {id, version, sortKey, payload, tags = []} = row.value;
                            value.pages[index].push({id, version, sortKey, payload, tags});
                            if (value.pages[index].length < limit) row.continue();
                        };
                        return;
                    }
                    const records = tx.objectStore('replica_documents').index(tag == null ? 'visible' : 'tagged').getAll(range, limit);
                    records.onsuccess = () => {
                        value.pages[index] = records.result.map(({id, version, sortKey, payload, tags = []}) => ({id, version, sortKey, payload, tags}));
                    };
                });
            };
        };
    };
    tx.oncomplete = () => resolve(JSON.stringify(value));
    tx.onabort = () => reject(tx.error || new Error('Replica query failed.'));
})"""
)
internal external fun replicaQueryPages(
    db: JsAny,
    scope: String,
    partition: String,
    queries: String,
): Promise<JsString>
