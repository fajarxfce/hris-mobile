@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserReplicaChangesMigrationTest {
    @Test
    fun versionTwelvePreservesCiphertextVersionsTagsTombstonesAndStaging() = runTest {
        val namespace = "replica-change-migration-" + kotlin.random.Random.nextInt()
        val previous = openReplicaChangePredecessor("$namespace.database").await<JsAny>()
        try {
            seedReplicaChangePredecessor(previous).await<JsAny?>()
        } finally {
            closeReplicaChangePredecessor(previous)
        }
        val database = createAppDatabase(namespace)
        try {
            verifyReplicaChangesMigration(database)
        } finally {
            database.close()
            deleteReplicaChangesMigrationDatabase("$namespace.database").await<JsAny?>()
        }
    }

    @Test
    fun versionSevenCopiesAlsoReceiveTheirInitialChangePosition() = runTest {
        val namespace = "replica-change-legacy-" + kotlin.random.Random.nextInt()
        seedReplicaChangesVersionSeven("$namespace.database").await<JsAny?>()
        val database = createAppDatabase(namespace)
        try {
            val scope = ReplicaScope("legacy-session")
            val first =
                assertIs<ReplicaChangePage.Items>(
                    database.replicas.changedDocuments(scope, "legacy/inbox")
                )
            assertEquals(
                listOf(
                    ReplicaChangedDocument(ReplicaDocument("message", 2, "01", "sealed-message"), 0)
                ),
                first.records,
            )
            val staged = assertNotNull(database.replicas.checkpoint(scope, "legacy/inbox"))
            assertTrue(database.replicas.commit(staged.handle, 3, "ready", emptyList(), true))
            val next =
                assertIs<ReplicaChangePage.Items>(
                    database.replicas.changedDocuments(scope, "legacy/inbox")
                )
            assertEquals(
                listOf(
                    ReplicaChangedDocument(ReplicaDocument("deleted", 4, "02", null), 0),
                    ReplicaChangedDocument(ReplicaDocument("message", 3, "01", "staged-message"), 0),
                ),
                next.records,
            )
        } finally {
            database.close()
            deleteReplicaChangesMigrationDatabase("$namespace.database").await<JsAny?>()
        }
    }
}

@JsFun(
    """name => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Replica migration database still open.'));
})"""
)
private external fun deleteReplicaChangesMigrationDatabase(name: String): Promise<JsAny?>

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 7);
    request.onupgradeneeded = () => {
        const db = request.result;
        const scope = db.createObjectStore('account_scope');
        scope.put('legacy-session', 'current'); scope.put('legacy-account', 'command-account');
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
        const heads = db.createObjectStore('replica_heads', {keyPath:'partition'});
        const generations = db.createObjectStore('replica_generations', {keyPath:['partition', 'generation']});
        const documents = db.createObjectStore('replica_documents', {keyPath:['partition', 'generation', 'id']});
        documents.createIndex('ordered', ['partition', 'generation', 'sortKey'], {unique:true});
        documents.createIndex('visible', ['partition', 'generation', 'visibleSort']);
        heads.put({partition:'legacy/inbox', activeGeneration:'active', stagingGeneration:'staged'});
        for (const [generation, revision, checkpoint] of [['active', 2, 'active-checkpoint'], ['staged', 3, 'staging-checkpoint']]) {
            generations.put({partition:'legacy/inbox', generation, sessionId:'legacy-session', revision, checkpoint});
        }
        documents.put({partition:'legacy/inbox', generation:'active', id:'message', version:2, sortKey:'01', visibleSort:'01', payload:'sealed-message'});
        documents.put({partition:'legacy/inbox', generation:'staged', id:'message', version:3, sortKey:'01', visibleSort:'01', payload:'staged-message'});
        documents.put({partition:'legacy/inbox', generation:'staged', id:'deleted', version:4, sortKey:'02', payload:null});
    };
    request.onsuccess = () => { request.result.close(); resolve(null); };
    request.onerror = () => reject(request.error);
})"""
)
private external fun seedReplicaChangesVersionSeven(name: String): Promise<JsAny?>
