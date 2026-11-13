@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise

/** One IndexedDB read transaction owns account, generation, interval and lookahead. */
@JsFun(
    """(db, scopeJson, partition, cursorJson, limit) => new Promise((resolve, reject) => {
    const scope = JSON.parse(scopeJson), cursor = JSON.parse(cursorJson);
    const {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readonly');
    let result = {type:cursor == null ? 'absent' : 'restart'}, failure = null;
    const owner = tx.objectStore('account_scope').get('current');
    const account = tx.objectStore('account_scope').get('command-account');
    account.onsuccess = () => {
        if (owner.result !== sessionId || (accountKey != null && account.result !== accountKey)) return;
        const head = tx.objectStore('replica_heads').get([ownerKey, partition]);
        head.onsuccess = () => {
            const generation = head.result?.activeGeneration;
            if (generation == null) return;
            const found = tx.objectStore('replica_generations').get([ownerKey, partition, generation]);
            found.onsuccess = () => {
                const row = found.result;
                if (!row) return;
                const handle = {scope, partition, generation};
                const checkpoint = {handle, revision:row.revision, payload:row.checkpoint, published:true};
                let window;
                if (cursor == null) window = {handle, afterRevision:null, throughRevision:row.revision};
                else if (cursor.type === 'after') {
                    if (cursor.handle.generation !== generation || cursor.revision > row.revision) return;
                    window = {handle, afterRevision:cursor.revision, throughRevision:row.revision};
                } else {
                    if (cursor.window.handle.generation !== generation || cursor.window.throughRevision > row.revision) return;
                    window = cursor.window;
                }
                const position = cursor?.type === 'within' ? cursor.position : null;
                const lower = [ownerKey, partition, generation, position?.revision ?? window.afterRevision ?? -1, position?.id ?? '\u007f'];
                const upper = [ownerKey, partition, generation, window.throughRevision, '\u007f'];
                result = {type:'items', checkpoint, window, records:[], next:null};
                if (indexedDB.cmp(lower, upper) >= 0) return;
                const pending = tx.objectStore('replica_documents').index('changed').openCursor(IDBKeyRange.bound(lower, upper, true, false));
                pending.onsuccess = () => {
                    const entry = pending.result;
                    if (!entry) return;
                    const value = entry.value;
                    if (!Number.isSafeInteger(value.changedAtRevision) || value.changedAtRevision < 0 || value.changedAtRevision > window.throughRevision) {
                        failure = new Error('Invalid replica change position.'); tx.abort(); return;
                    }
                    if (result.records.length === limit) {
                        const last = result.records[result.records.length - 1];
                        result.next = {window, position:{revision:last.changedAtRevision, id:last.document.id}};
                        return;
                    }
                    const {id, version, sortKey, payload, tags = []} = value;
                    result.records.push({document:{id, version, sortKey, payload, tags}, changedAtRevision:value.changedAtRevision});
                    entry.continue();
                };
            };
        };
    };
    tx.oncomplete = () => resolve(JSON.stringify(result));
    tx.onabort = () => reject(failure || tx.error || new Error('Replica change read failed.'));
})"""
)
internal external fun replicaChangedDocuments(
    db: JsAny,
    scope: String,
    partition: String,
    cursor: String,
    limit: Int,
): Promise<JsString>
