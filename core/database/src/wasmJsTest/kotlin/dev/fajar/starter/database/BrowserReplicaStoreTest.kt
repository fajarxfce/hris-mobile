@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BrowserReplicaStoreTest {
    @Test
    fun upgradeRetainsPublishedStagedAndDeletedRecords() = runTest {
        val namespace = "replica-owner-upgrade-" + kotlin.random.Random.nextInt()
        seedLegacyReplicaDatabase("$namespace.database").await<JsAny?>()
        val database = createAppDatabase(namespace)
        try {
            verifyLegacyReplicaRetention(database)
        } finally {
            database.close()
            deleteReplicaDatabase("$namespace.database").await<JsAny?>()
        }
    }

    @Test
    fun rejectedUpgradeClosesTheLateDatabaseConnection() = runTest {
        val namespace = "replica-blocked-" + kotlin.random.Random.nextInt()
        val old = holdReplicaDatabase("$namespace.database").await<JsAny>()
        val database = createAppDatabase(namespace)
        try {
            assertFails { database.replicas.checkpoint(ReplicaScope("session"), "company/inbox") }
        } finally {
            closeReplicaDatabase(old)
            database.close()
            deleteReplicaDatabase("$namespace.database").await<JsAny?>()
        }
    }

    @Test
    fun indexedDbReplicationContract() = runTest {
        val namespace = "replica-contract-" + kotlin.random.Random.nextInt()
        val database = createAppDatabase(namespace)
        try {
            verifyReplicaPublication(database)
            verifyReplicaCheckpointObservation(database)
            verifyReplicaAtomicity(database)
            verifyReplicaTombstones(database)
            verifyReplicaIsolation(database)
            verifyReplicaCancellation(database)
            verifyReplicaPaginationAndBounds(database)
            verifyReplicaAccountOwnership(database)
            verifyReplicaAccountReplacement(database)
            verifyReplicaSnapshotReplacement(database)
            verifyReplicaSnapshotCancellationAndBounds(database)
        } finally {
            database.close()
            deleteReplicaDatabase("$namespace.database").await<JsAny?>()
        }
    }

    @Test
    fun pendingReplacementSurvivesReopen() = runTest {
        val namespace = "replica-restart-" + kotlin.random.Random.nextInt()
        var database = createAppDatabase(namespace)
        val handle = ReplicaHandle(ReplicaScope("session", "account"), "company/inbox", "staging")
        try {
            database.accounts.activate(handle.scope.sessionId, handle.scope.accountKey)
            assertTrue(
                database.replicas.begin(
                    handle.scope,
                    handle.partition,
                    handle.generation,
                    "bootstrap",
                    null,
                )
            )
            val document = ReplicaDocument("message", 2, "01-message", "encrypted-message")
            assertTrue(database.replicas.commit(handle, 0, "next-page", listOf(document), false))
            database.close()
            database = createAppDatabase(namespace)
            val resumed = handle.copy(scope = handle.scope.copy(sessionId = "reopened-login"))
            database.accounts.activate(resumed.scope.sessionId, resumed.scope.accountKey)
            assertTrue(
                database.replicas
                    .observe(resumed.scope, resumed.partition)
                    .first()
                    .documents
                    .isEmpty()
            )
            assertEquals(
                "next-page",
                database.replicas.checkpoint(resumed.scope, resumed.partition)!!.payload,
            )
            assertTrue(database.replicas.commit(resumed, 1, "caught-up", emptyList(), true))
            assertEquals(
                document,
                database.replicas.document(resumed.scope, resumed.partition, document.id),
            )
        } finally {
            database.close()
            deleteReplicaDatabase("$namespace.database").await<JsAny?>()
        }
    }
}

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null); request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Replica database still has an owner.'));
})"""
)
private external fun deleteReplicaDatabase(name: String): Promise<JsAny?>

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 1);
    request.onupgradeneeded = () => request.result.createObjectStore('inbox', {keyPath:'id'});
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
})"""
)
private external fun holdReplicaDatabase(name: String): Promise<JsAny>

@JsFun("(db) => db.close()") private external fun closeReplicaDatabase(db: JsAny)

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
private external fun seedLegacyReplicaDatabase(name: String): Promise<JsAny?>
