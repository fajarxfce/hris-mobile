@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserCommandArchiveTest {
    private val scope = CommandScope("session", "account")
    private val original =
        CommandRecord(
            "company/commands",
            "operation",
            "resource",
            "order",
            "sealed-request",
            "sealed-state",
        )

    @Test
    fun completionAndInsertionFenceSurviveReopeningAndReauthentication() = runTest {
        val namespace = "archive-restart-" + kotlin.random.Random.nextInt()
        var database = createAppDatabase(namespace)
        try {
            database.accounts.activate(scope.sessionId, scope.accountKey)
            database.commands.insert(scope, original)
            assertTrue(
                database.commands.update(
                    scope,
                    original.partition,
                    original.id,
                    0,
                    "sealed-completed",
                )
            )
            assertTrue(
                database.commands.archive(
                    scope,
                    original.copy(revision = 1, state = "sealed-completed"),
                    "sealed-proof",
                )
            )
            database.close()
            database = createAppDatabase(namespace)
            val resumed = scope.copy(sessionId = "new-session")
            database.accounts.activate(resumed.sessionId, resumed.accountKey)
            assertNull(database.commands.archived(scope, original.partition, original.id))
            assertEquals(
                CommandArchive(
                    original.copy(state = "sealed-completed", revision = 1),
                    "sealed-proof",
                ),
                database.commands.archived(resumed, original.partition, original.id),
            )
            assertEquals(
                CommandInsertResult.AlreadyStored,
                database.commands.insert(resumed, original),
            )
            assertNull(database.commands.get(resumed, original.partition, original.id))
            assertTrue(database.commands.containsRecords())
        } finally {
            database.close()
            deleteArchiveDatabase(namespace + ".database").await<JsAny?>()
        }
    }

    @Test
    fun abortedDeleteRollsBackBothTheArchiveAndPendingMutation() = runTest {
        val namespace = "archive-abort-" + kotlin.random.Random.nextInt()
        val database = createAppDatabase(namespace)
        try {
            database.accounts.activate(scope.sessionId, scope.accountKey)
            database.commands.insert(scope, original)
            val restore = interruptArchiveDelete(namespace + ".database")
            try {
                assertFails { database.commands.archive(scope, original, "sealed-proof") }
            } finally {
                restoreArchiveDelete(restore)
            }
            assertEquals(original, database.commands.get(scope, original.partition, original.id))
            assertNull(database.commands.archived(scope, original.partition, original.id))
            assertTrue(database.commands.archive(scope, original, "sealed-proof"))
            assertNull(database.commands.get(scope, original.partition, original.id))
            assertEquals(
                CommandArchive(original, "sealed-proof"),
                database.commands.archived(scope, original.partition, original.id),
            )
        } finally {
            database.close()
            deleteArchiveDatabase(namespace + ".database").await<JsAny?>()
        }
    }

    @Test
    fun versionNineMigrationPreservesBoundCommandsAndAddsArchiveStorage() = runTest {
        val namespace = "archive-migration-" + kotlin.random.Random.nextInt()
        seedArchiveDatabase(namespace + ".database").await<JsAny?>()
        val database = createAppDatabase(namespace)
        try {
            assertEquals(original, database.commands.get(scope, original.partition, original.id))
            assertNull(database.commands.archived(scope, original.partition, original.id))
            assertTrue(database.commands.archive(scope, original, "sealed-proof"))
            assertEquals(
                CommandArchive(original, "sealed-proof"),
                database.commands.archived(scope, original.partition, original.id),
            )
        } finally {
            database.close()
            deleteArchiveDatabase(namespace + ".database").await<JsAny?>()
        }
    }
}

@JsFun(
    """(name) => {
    const previous = IDBObjectStore.prototype.delete;
    IDBObjectStore.prototype.delete = function(key) {
        if (this.name === 'durable_commands' && this.transaction.db.name === name) {
            this.transaction.abort(); return null;
        }
        return previous.call(this, key);
    };
    return previous;
}"""
)
private external fun interruptArchiveDelete(name: String): JsAny

@JsFun("(previous) => { IDBObjectStore.prototype.delete = previous; }")
private external fun restoreArchiveDelete(previous: JsAny)

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null); request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Archive database still owned.'));
})"""
)
private external fun deleteArchiveDatabase(name: String): Promise<JsAny?>

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 9);
    request.onupgradeneeded = () => {
        const db = request.result;
        const scopes = db.createObjectStore('account_scope');
        scopes.put('session', 'current'); scopes.put('account', 'command-account');
        const commands = db.createObjectStore('durable_commands', {keyPath:['accountKey', 'partition', 'id']});
        commands.createIndex('account', 'accountKey');
        commands.createIndex('resource', ['accountKey', 'partition', 'resourceId'], {unique:true});
        commands.createIndex('ordered', ['accountKey', 'partition', 'sortKey'], {unique:true});
        commands.add({accountKey:'account', partition:'company/commands', id:'operation', resourceId:'resource', sortKey:'order', request:'sealed-request', state:'sealed-state', revision:0});
    };
    request.onsuccess = () => { request.result.close(); resolve(null); };
    request.onerror = () => reject(request.error);
})"""
)
private external fun seedArchiveDatabase(name: String): Promise<JsAny?>
