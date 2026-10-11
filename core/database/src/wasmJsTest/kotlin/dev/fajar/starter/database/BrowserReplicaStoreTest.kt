@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BrowserReplicaStoreTest {
    @Test
    fun rejectedUpgradeClosesTheLateDatabaseConnection() = runTest {
        val namespace = "replica-blocked-" + kotlin.random.Random.nextInt()
        val old = holdReplicaDatabase("$namespace.database").await<JsAny>()
        val database = createAppDatabase(namespace)
        try {
            assertFails { database.replicas.checkpoint("session", "company/inbox") }
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
        val handle = ReplicaHandle("session", "company/inbox", "staging")
        try {
            database.accounts.activate(handle.sessionId)
            assertTrue(
                database.replicas.begin(
                    handle.sessionId,
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
            database.accounts.activate(handle.sessionId)
            assertTrue(
                database.replicas
                    .observe(handle.sessionId, handle.partition)
                    .first()
                    .documents
                    .isEmpty()
            )
            assertEquals(
                "next-page",
                database.replicas.checkpoint(handle.sessionId, handle.partition)!!.payload,
            )
            assertTrue(database.replicas.commit(handle, 1, "caught-up", emptyList(), true))
            assertEquals(
                document,
                database.replicas.document(handle.sessionId, handle.partition, document.id),
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
