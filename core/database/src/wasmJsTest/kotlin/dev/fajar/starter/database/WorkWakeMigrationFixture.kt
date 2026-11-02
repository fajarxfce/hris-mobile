@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise

// Frozen version10 schema creates a genuine earlier database for the additive upgrade test.
@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 10);
    let settled = false;
    request.onupgradeneeded = event => {
        const db = request.result;
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
internal external fun openPreviousWakeDatabase(name: String): Promise<JsAny>
