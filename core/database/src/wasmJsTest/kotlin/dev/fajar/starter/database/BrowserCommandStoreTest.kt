@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BrowserCommandStoreTest {
    @Test
    fun immutableCommandsUseAtomicRevisionChecks() = runTest {
        withCommandDatabase(::verifyCommandAtomicity)
    }

    @Test
    fun commandsSurviveLogoutButRequireTheSameAdmittedAccount() = runTest {
        withCommandDatabase(::verifyCommandOwnership)
    }

    @Test
    fun commandPagesAndStorageAreBounded() = runTest { withCommandDatabase(::verifyCommandBounds) }

    @Test
    fun cancellationLeavesAnAbsentOrCompleteTransaction() = runTest {
        withCommandDatabase(::verifyCommandCancellation)
    }

    @Test
    fun reopeningPreservesACommandForTheSameAccountAfterLogin() = runTest {
        val namespace = "command-restart-" + kotlin.random.Random.nextInt()
        var database = createAppDatabase(namespace)
        val scope = CommandScope("session", "account")
        val entry =
            CommandRecord(
                "company/inbox",
                "operation",
                "item",
                "order",
                "sealed-request",
                "sealed-pending",
            )
        try {
            database.accounts.activate(scope.sessionId, scope.accountKey)
            assertEquals(CommandInsertResult.Stored, database.commands.insert(scope, entry))
            assertTrue(
                database.commands.update(scope, entry.partition, entry.id, 0, "sealed-uncertain")
            )
            database.close()
            database = createAppDatabase(namespace)
            val newLogin = scope.copy(sessionId = "replacement-login")
            database.accounts.activate(newLogin.sessionId, newLogin.accountKey)
            assertEquals(
                entry.copy(state = "sealed-uncertain", revision = 1),
                database.commands.get(newLogin, entry.partition, entry.id),
            )
            assertNull(database.commands.get(scope, entry.partition, entry.id))
        } finally {
            database.close()
            deleteCommandDatabase(namespace + ".database").await<JsAny?>()
        }
    }

    @Test
    fun earlierDatabaseUpgradesRetainTheInbox() = runTest {
        val namespace = "command-upgrade-" + kotlin.random.Random.nextInt()
        createLegacyCommandDatabase(namespace + ".database").await<JsAny?>()
        val database = createAppDatabase(namespace)
        try {
            assertEquals("existing", database.inbox.observe().first().single().id)
            assertFalse(database.commands.containsRecords())
            val scope = CommandScope("session", "account")
            database.accounts.activate(scope.sessionId, scope.accountKey)
            assertEquals(
                CommandInsertResult.Stored,
                database.commands.insert(
                    scope,
                    CommandRecord(
                        "company/inbox",
                        "operation",
                        "item",
                        "order",
                        "sealed-request",
                        "sealed-pending",
                    ),
                ),
            )
        } finally {
            database.close()
            deleteCommandDatabase(namespace + ".database").await<JsAny?>()
        }
    }
}

private suspend fun withCommandDatabase(block: suspend (AppDatabase) -> Unit) {
    val namespace = "command-contract-" + kotlin.random.Random.nextInt()
    val database = createAppDatabase(namespace)
    try {
        block(database)
    } finally {
        database.close()
        deleteCommandDatabase(namespace + ".database").await<JsAny?>()
    }
}

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null); request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Command database still has an owner.'));
})"""
)
private external fun deleteCommandDatabase(name: String): Promise<JsAny?>

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 1);
    request.onupgradeneeded = () => request.result.createObjectStore('inbox', {keyPath:'id'}).add({id:'existing', title:'Retained', body:'Fixture', destination:'inbox', createdAtEpochMillis:1, read:false});
    request.onsuccess = () => { request.result.close(); resolve(null); };
    request.onerror = () => reject(request.error);
})"""
)
private external fun createLegacyCommandDatabase(name: String): Promise<JsAny?>
