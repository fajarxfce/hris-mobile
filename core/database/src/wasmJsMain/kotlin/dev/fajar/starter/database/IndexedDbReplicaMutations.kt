@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise

@JsFun(
    """(db, scopeJson, mutationsJson, wakeJson, archiveJson, insertionJson) => new Promise((resolve, reject) => {
    const scope = JSON.parse(scopeJson), mutations = JSON.parse(mutationsJson), wake = JSON.parse(wakeJson), archive = JSON.parse(archiveJson), insertion = JSON.parse(insertionJson);
    const {sessionId, accountKey = null} = scope, ownerKey = accountKey ?? '';
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents', ...(wake == null ? [] : ['work_wakes']), ...(archive == null && insertion == null ? [] : ['durable_commands', 'command_archives'])], 'readwrite');
    const heads = tx.objectStore('replica_heads'), generations = tx.objectStore('replica_generations'), documents = tx.objectStore('replica_documents');
    const prepared = [];
    let changed = false, insertionResult = 'ScopeChanged';
    const write = index => {
        if (index === prepared.length) {
            if (archive != null) {
                const record = archive.record;
                tx.objectStore('command_archives').add({...record, accountKey, evidence:archive.evidence});
                tx.objectStore('durable_commands').delete([accountKey, record.partition, record.id]);
            }
            if (insertion != null) {
                tx.objectStore('durable_commands').add({...insertion, accountKey});
                insertionResult = 'Stored';
            }
            changed = true;
            return;
        }
        const {mutation:m, partition, head, current} = prepared[index];
        if (m.type === 'check') { write(index + 1); return; }
        if (m.type === 'replace') {
            heads.delete([ownerKey, partition]);
            generations.delete(IDBKeyRange.bound([ownerKey, partition, ''], [ownerKey, partition, '\uffff']));
            documents.delete(IDBKeyRange.bound([ownerKey, partition, '', ''], [ownerKey, partition, '\uffff', '\uffff']));
            if (m.snapshot != null) {
                const {generation, checkpoint} = m.snapshot;
                generations.put({ownerKey, partition, generation, revision:0, checkpoint});
                for (const item of m.snapshot.documents) {
                    documents.put({...item, ownerKey, partition, generation, changedAtRevision:0, visibleSort:item.payload == null ? undefined : item.sortKey,
                        tagKeys:item.payload == null ? [] : (item.tags ?? []).map(tag => [ownerKey, partition, generation, tag, item.sortKey])});
                }
                heads.put({ownerKey, partition, activeGeneration:generation, stagingGeneration:null});
            }
            write(index + 1);
            return;
        }
        const generation = m.handle.generation;
        const nextDocument = position => {
            if (position === m.documents.length) {
                generations.put({...current, revision:m.expectedRevision + 1, checkpoint:m.checkpoint});
                if (m.publish) {
                    const old = head.activeGeneration;
                    if (old != null && old !== generation) {
                        generations.delete([ownerKey, partition, old]);
                        documents.delete(IDBKeyRange.bound([ownerKey, partition, old, ''], [ownerKey, partition, old, '\uffff']));
                    }
                    heads.put({ownerKey, partition, activeGeneration:generation, stagingGeneration:null});
                }
                write(index + 1);
                return;
            }
            const item = m.documents[position], previous = documents.get([ownerKey, partition, generation, item.id]);
            previous.onsuccess = () => {
                const old = previous.result;
                if (!old || (old.version <= item.version && !(old.version === item.version && old.payload == null && item.payload != null))) {
                    const unchanged = old && old.version === item.version && old.sortKey === item.sortKey && old.payload === item.payload &&
                        JSON.stringify([...(old.tags ?? [])].sort()) === JSON.stringify([...(item.tags ?? [])].sort());
                    if (!unchanged) documents.put({...item, ownerKey, partition, generation, changedAtRevision:m.expectedRevision + 1, visibleSort:item.payload == null ? undefined : item.sortKey,
                        tagKeys:item.payload == null ? [] : (item.tags ?? []).map(tag => [ownerKey, partition, generation, tag, item.sortKey])});
                }
                nextDocument(position + 1);
            };
        };
        nextDocument(0);
    };
    const admitWake = () => {
        const publish = () => {
            if (wake != null) tx.objectStore('work_wakes').put({key:wake.key, generation:wake.generation, pending:true});
            write(0);
        };
        if (wake == null) { publish(); return; }
        const store = tx.objectStore('work_wakes'), current = store.get(wake.key);
        current.onsuccess = () => {
            if (current.result?.generation === wake.generation) return;
            if (current.result) { publish(); return; }
            const count = store.count();
            count.onsuccess = () => { if (count.result < wake.maxKeys) publish(); };
        };
    };
    const preflight = index => {
        if (index === mutations.length) { admitWake(); return; }
        const m = mutations[index], partition = m.type === 'commit' ? m.handle.partition : m.partition;
        const found = heads.get([ownerKey, partition]);
        found.onsuccess = () => {
            const head = found.result, generation = head?.stagingGeneration ?? head?.activeGeneration;
            const inspect = current => {
                const ready = () => { prepared.push({mutation:m, partition, head, current}); preflight(index + 1); };
                if (m.type === 'commit') {
                    if (generation === m.handle.generation && current?.revision === m.expectedRevision) ready();
                    return;
                }
                const expected = m.expected;
                if (current == null ? expected != null :
                    expected == null || expected.handle.scope.sessionId !== sessionId || (expected.handle.scope.accountKey ?? null) !== accountKey ||
                    expected.handle.partition !== partition || expected.handle.generation !== current.generation ||
                    expected.revision !== current.revision || expected.payload !== current.checkpoint ||
                    expected.published !== (head.activeGeneration === generation)) return;
                if (m.type === 'check' || m.snapshot == null) { ready(); return; }
                const existing = generations.get([ownerKey, partition, m.snapshot.generation]);
                existing.onsuccess = () => { if (!existing.result) ready(); };
            };
            if (generation == null) { inspect(null); return; }
            const row = generations.get([ownerKey, partition, generation]);
            row.onsuccess = () => { if (row.result) inspect(row.result); };
        };
    };
    const admitInsertion = () => {
        insertionResult = null;
        const commands = tx.objectStore('durable_commands'), archives = tx.objectStore('command_archives');
        const key = [accountKey, insertion.partition, insertion.id];
        const current = commands.get(key), archived = archives.get(key);
        archived.onsuccess = () => {
            if (current.result && archived.result) { tx.abort(); return; }
            const previous = current.result || archived.result;
            if (previous) {
                if (previous.request !== insertion.request || previous.resourceId !== insertion.resourceId || previous.sortKey !== insertion.sortKey) {
                    tx.abort(); return;
                }
                insertionResult = 'AlreadyStored'; return;
            }
            const occupied = commands.index('resource').getKey([accountKey, insertion.partition, insertion.resourceId]);
            occupied.onsuccess = () => {
                if (occupied.result !== undefined) { insertionResult = 'ResourceOccupied'; return; }
                const total = commands.count(), own = commands.index('account').count(accountKey);
                own.onsuccess = () => {
                    if (total.result >= 512 || own.result >= 128) { insertionResult = 'CapacityExceeded'; return; }
                    preflight(0);
                };
            };
        };
    };
    const admitArchive = () => {
        if (archive == null) { preflight(0); return; }
        const expected = archive.record, key = [accountKey, expected.partition, expected.id];
        const matches = value => value && value.accountKey === accountKey && value.partition === expected.partition && value.id === expected.id &&
            value.resourceId === expected.resourceId && value.sortKey === expected.sortKey && value.request === expected.request &&
            value.state === expected.state && value.revision === expected.revision;
        const retained = tx.objectStore('command_archives').get(key);
        retained.onsuccess = () => {
            if (retained.result) { changed = matches(retained.result) && retained.result.evidence === archive.evidence; return; }
            const current = tx.objectStore('durable_commands').get(key);
            current.onsuccess = () => { if (matches(current.result)) preflight(0); };
        };
    };
    const owner = tx.objectStore('account_scope').get('current');
    const account = tx.objectStore('account_scope').get('command-account');
    account.onsuccess = () => {
        if (owner.result === sessionId && (accountKey == null || account.result === accountKey)) {
            if (insertion == null) admitArchive(); else admitInsertion();
        }
    };
    tx.oncomplete = () => resolve(JSON.stringify(insertion == null ? changed : insertionResult));
    tx.onabort = () => reject(tx.error || new Error('Replica mutation failed.'));
})"""
)
internal external fun mutateReplicas(
    db: JsAny,
    scope: String,
    mutations: String,
    wake: String,
    archive: String,
    insertion: String,
): Promise<JsString>
