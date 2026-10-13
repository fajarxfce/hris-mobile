@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 8);
    request.onupgradeneeded = () => {
        const db = request.result;
        const scope = db.createObjectStore('account_scope');
        scope.put('indexed-session', 'current'); scope.put('indexed-account', 'command-account');
        db.createObjectStore('dashboard');
        db.createObjectStore('inbox', {keyPath:'id'});
        db.createObjectStore('activity_preferences', {keyPath:'activityId'});
        db.createObjectStore('dashboard_outbox', {keyPath:'sequence', autoIncrement:true}).createIndex('operationId', 'operationId', {unique:true});
        db.createObjectStore('transfers', {keyPath:'id'});
        db.createObjectStore('transfer_chunks', {keyPath:['transferId', 'offset']});
        db.createObjectStore('durable_blobs', {keyPath:['accountKey', 'partition', 'id']});
        db.createObjectStore('durable_blob_chunks', {keyPath:['accountKey', 'partition', 'id', 'offset']});
        const commands = db.createObjectStore('durable_commands', {keyPath:['accountKey', 'partition', 'id']});
        commands.createIndex('account', 'accountKey');
        commands.createIndex('resource', ['accountKey', 'partition', 'resourceId'], {unique:true});
        commands.createIndex('ordered', ['accountKey', 'partition', 'sortKey'], {unique:true});
        const heads = db.createObjectStore('replica_heads', {keyPath:['ownerKey', 'partition']});
        const generations = db.createObjectStore('replica_generations', {keyPath:['ownerKey', 'partition', 'generation']});
        const documents = db.createObjectStore('replica_documents', {keyPath:['ownerKey', 'partition', 'generation', 'id']});
        documents.createIndex('ordered', ['ownerKey', 'partition', 'generation', 'sortKey'], {unique:true});
        documents.createIndex('visible', ['ownerKey', 'partition', 'generation', 'visibleSort']);
        const ownerKey = 'indexed-account', partition = 'indexed/records';
        heads.put({ownerKey, partition, activeGeneration:'active', stagingGeneration:'staged'});
        generations.put({ownerKey, partition, generation:'active', revision:2, checkpoint:'active-cursor'});
        generations.put({ownerKey, partition, generation:'staged', revision:3, checkpoint:'staging-cursor'});
        documents.put({ownerKey, partition, generation:'active', id:'record', version:2, sortKey:'01', visibleSort:'01', payload:'original-ciphertext'});
        documents.put({ownerKey, partition, generation:'staged', id:'record', version:3, sortKey:'01', visibleSort:'01', payload:'staged-ciphertext'});
        documents.put({ownerKey, partition, generation:'staged', id:'deleted', version:4, sortKey:'02', payload:null});
    };
    request.onsuccess = () => { request.result.close(); resolve(null); };
    request.onerror = () => reject(request.error);
})"""
)
internal external fun seedIndexedReplicaDatabase(name: String): Promise<JsAny?>
