@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserBlobStoreTest {
    @Test
    fun checkpointsPreserveContentAndRejectObsoleteOwners() = runTest {
        withBlobDatabase(::verifyBlobCheckpoints)
    }

    @Test
    fun progressAndBlocksCommitTogether() = runTest { withBlobDatabase(::verifyBlobAtomicity) }

    @Test
    fun accountChangesHideRetainedFilesAndRejectLateWrites() = runTest {
        withBlobDatabase(::verifyBlobOwnership)
    }

    @Test
    fun fileBlockAndEnvelopeBoundsRejectPartialWrites() = runTest {
        withBlobDatabase(::verifyBlobBounds)
    }

    @Test
    fun capacityIsSharedAcrossPartitionsAndPagesAreBounded() = runTest {
        withBlobDatabase(::verifyBlobQuotaAndPages)
    }

    @Test
    fun declaredBytesReserveGlobalCapacityBeforeImport() = runTest {
        withBlobDatabase(::verifyBlobGlobalReservation)
    }

    @Test
    fun abandonedAccountsCannotGrowTheHeaderCountWithoutBound() = runTest {
        withBlobDatabase(::verifyBlobGlobalItemLimit)
    }

    @Test
    fun cancelledWritesLeaveCompleteTransactions() = runTest {
        withBlobDatabase(::verifyBlobCancellation)
    }

    @Test
    fun independentConnectionsCannotOverbookTheSameCapacity() = runTest {
        val namespace = "blob-concurrent-" + kotlin.random.Random.nextInt()
        val first = createAppDatabase(namespace)
        val second = createAppDatabase(namespace)
        try {
            verifyBlobCapacityAcrossConnections(first, second)
        } finally {
            first.close()
            second.close()
            deleteBlobDatabase(namespace + ".database").await<JsAny?>()
        }
    }

    @Test
    fun reopeningRetainsCompletedContentForTheSameAccount() = runTest {
        val namespace = "blob-restart-" + kotlin.random.Random.nextInt()
        var database = createAppDatabase(namespace)
        val scope = BlobScope("session", "account")
        val original = BlobRecord("company/files", "document", 3, "sealed-metadata")
        val block = BlobChunkRecord(0, 3, "sealed-content")
        try {
            database.accounts.activate(scope.sessionId, scope.accountKey)
            assertEquals(BlobInsertResult.Stored, database.blobs.insert(scope, original, 100, 10))
            assertTrue(database.blobs.append(scope, original.partition, original.id, 0, block))
            assertTrue(
                database.blobs.complete(scope, original.partition, original.id, 1, "sealed-proof")
            )
            assertTrue(
                database.blobs.updateState(
                    scope,
                    original.partition,
                    original.id,
                    2,
                    "sealed-delivery",
                )
            )
            database.close()
            database = createAppDatabase(namespace)
            val resumed = scope.copy(sessionId = "replacement-login")
            database.accounts.activate(resumed.sessionId, resumed.accountKey)
            assertEquals(
                original.copy(
                    storedBytes = 3,
                    completion = "sealed-proof",
                    revision = 3,
                    state = "sealed-delivery",
                ),
                database.blobs.get(resumed, original.partition, original.id),
            )
            assertEquals(block, database.blobs.chunk(resumed, original.partition, original.id, 0))
            assertNull(database.blobs.chunk(scope, original.partition, original.id, 0))
        } finally {
            database.close()
            deleteBlobDatabase(namespace + ".database").await<JsAny?>()
        }
    }

    @Test
    fun versionSixUpgradePreservesBoundCommands() = runTest {
        val namespace = "blob-upgrade-" + kotlin.random.Random.nextInt()
        createBlobPredecessor(namespace + ".database").await<JsAny?>()
        val database = createAppDatabase(namespace)
        try {
            val original =
                CommandRecord(
                    "company/commands",
                    "operation",
                    "resource",
                    "order",
                    "sealed-request",
                    "sealed-state",
                    4,
                )
            assertEquals(
                original,
                database.commands.get(
                    CommandScope("session", "account"),
                    original.partition,
                    original.id,
                ),
            )
            assertFalse(database.blobs.containsRecords())
            val scope = BlobScope("session", "account")
            val file = BlobRecord("company/files", "file", 3, "sealed-metadata")
            assertEquals(BlobInsertResult.Stored, database.blobs.insert(scope, file, 10, 1))
            assertTrue(
                database.blobs.append(
                    scope,
                    file.partition,
                    file.id,
                    0,
                    BlobChunkRecord(0, 3, "sealed-block"),
                )
            )
            assertEquals(
                original,
                database.commands.get(
                    CommandScope("session", "account"),
                    original.partition,
                    original.id,
                ),
            )
        } finally {
            database.close()
            deleteBlobDatabase(namespace + ".database").await<JsAny?>()
        }
    }
}

private suspend fun withBlobDatabase(block: suspend (AppDatabase) -> Unit) {
    val namespace = "blob-contract-" + kotlin.random.Random.nextInt()
    val database = createAppDatabase(namespace)
    try {
        block(database)
    } finally {
        database.close()
        deleteBlobDatabase(namespace + ".database").await<JsAny?>()
    }
}

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null); request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Blob database still has an owner.'));
})"""
)
private external fun deleteBlobDatabase(name: String): Promise<JsAny?>

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 6);
    request.onupgradeneeded = () => {
        const db = request.result;
        db.createObjectStore('inbox', {keyPath:'id'});
        db.createObjectStore('dashboard');
        db.createObjectStore('activity_preferences', {keyPath:'activityId'});
        const outbox = db.createObjectStore('dashboard_outbox', {keyPath:'sequence', autoIncrement:true});
        outbox.createIndex('operationId', 'operationId', {unique:true});
        const scope = db.createObjectStore('account_scope');
        scope.put('session', 'current'); scope.put('account', 'command-account');
        db.createObjectStore('transfers', {keyPath:'id'});
        db.createObjectStore('transfer_chunks', {keyPath:['transferId', 'offset']});
        db.createObjectStore('replica_heads', {keyPath:'partition'});
        db.createObjectStore('replica_generations', {keyPath:['partition', 'generation']});
        const replicas = db.createObjectStore('replica_documents', {keyPath:['partition', 'generation', 'id']});
        replicas.createIndex('ordered', ['partition', 'generation', 'sortKey'], {unique:true});
        replicas.createIndex('visible', ['partition', 'generation', 'visibleSort']);
        const commands = db.createObjectStore('durable_commands', {keyPath:['accountKey', 'partition', 'id']});
        commands.createIndex('account', 'accountKey');
        commands.createIndex('resource', ['accountKey', 'partition', 'resourceId'], {unique:true});
        commands.createIndex('ordered', ['accountKey', 'partition', 'sortKey'], {unique:true});
        commands.add({accountKey:'account', partition:'company/commands', id:'operation', resourceId:'resource', sortKey:'order', request:'sealed-request', state:'sealed-state', revision:4});
    };
    request.onsuccess = () => { request.result.close(); resolve(null); };
    request.onerror = () => reject(request.error);
})"""
)
private external fun createBlobPredecessor(name: String): Promise<JsAny?>
