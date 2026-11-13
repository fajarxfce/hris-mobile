@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise

// Frozen IndexedDB12 schema, before adding local change positions.
@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 12);
    let settled = false;
    request.onupgradeneeded = event => {
        const db = request.result;
        if (event.oldVersion < 12) db.createObjectStore('device_records', {keyPath:['namespace', 'id']});
        if (event.oldVersion < 11) {
            db.createObjectStore('work_wakes', {keyPath:'key'});
        }
        if (event.oldVersion < 10) {
            db.createObjectStore('command_archives', {keyPath:['accountKey', 'partition', 'id']});
        }
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
        if (event.oldVersion < 9) {
            request.transaction.objectStore('replica_documents').createIndex('tagged', 'tagKeys', {multiEntry:true, unique:true});
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
internal external fun openReplicaChangePredecessor(name: String): Promise<JsAny>

@JsFun(
    """db => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'replica_heads', 'replica_generations', 'replica_documents'], 'readwrite');
    const scope = tx.objectStore('account_scope'); scope.put('migration-session', 'current'); scope.put('migration-account', 'command-account');
    const ownerKey = 'migration-account', partition = 'migration/records';
    tx.objectStore('replica_heads').put({ownerKey, partition, activeGeneration:'active', stagingGeneration:'staged'});
    const generations = tx.objectStore('replica_generations');
    generations.put({ownerKey, partition, generation:'active', revision:12, checkpoint:'original-checkpoint'});
    generations.put({ownerKey, partition, generation:'staged', revision:13, checkpoint:'staged-checkpoint'});
    const records = tx.objectStore('replica_documents');
    records.put({ownerKey, partition, generation:'active', id:'value', version:900, sortKey:'01', visibleSort:'01', payload:'original-ciphertext', tags:['visible'], tagKeys:[[ownerKey, partition, 'active', 'visible', '01']]});
    records.put({ownerKey, partition, generation:'active', id:'deleted', version:901, sortKey:'02', payload:null, tags:[], tagKeys:[]});
    records.put({ownerKey, partition, generation:'staged', id:'value', version:902, sortKey:'01', visibleSort:'01', payload:'staged-ciphertext', tags:['hidden'], tagKeys:[[ownerKey, partition, 'staged', 'hidden', '01']]});
    tx.oncomplete = () => resolve(null);
    tx.onabort = () => reject(tx.error || new Error('Replica migration seed failed.'));
})"""
)
internal external fun seedReplicaChangePredecessor(db: JsAny): Promise<JsAny?>

@JsFun("db => db.close()") internal external fun closeReplicaChangePredecessor(db: JsAny)
