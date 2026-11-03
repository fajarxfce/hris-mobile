package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

suspend fun verifyAbsentArchiveIdentityAndOwnership(database: AppDatabase) {
    val scope = CommandScope("session", "account")
    val row =
        CommandRecord("reply", "reply-id", "resource", "sort", "sealed-request", "sealed-state")
    val store = database.commands
    database.accounts.activate(scope.sessionId, scope.accountKey)
    assertTrue(store.archiveAbsent(scope, row, "proof"))
    assertTrue(store.observeAll(scope, row.partition).first().isEmpty())
    assertNull(store.get(scope, row.partition, row.id))
    assertEquals(CommandArchive(row, "proof"), store.archived(scope, row.partition, row.id))
    assertTrue(store.archiveAbsent(scope, row, "proof"))
    assertFalse(store.archiveAbsent(scope, row, "other-proof"))
    assertFalse(store.archiveAbsent(scope, row.copy(state = "changed"), "proof"))
    assertEquals(CommandInsertResult.AlreadyStored, store.insert(scope, row))
    assertFails { store.insert(scope, row.copy(request = "changed")) }
    val other = scope.copy(accountKey = "other-account", sessionId = "other-session")
    database.accounts.activate(other.sessionId, other.accountKey)
    assertFalse(store.archiveAbsent(scope, row, "proof"))
    assertNull(store.archived(other, row.partition, row.id))
    assertTrue(store.archiveAbsent(other, row, "other-proof"))
    database.accounts.activate("restored", scope.accountKey)
    assertFalse(store.archiveAbsent(scope, row, "proof"))
    assertEquals(
        CommandArchive(row, "proof"),
        store.archived(scope.copy(sessionId = "restored"), row.partition, row.id),
    )
}

suspend fun verifyAbsentArchiveActiveConflictAndCapacity(database: AppDatabase) = coroutineScope {
    val scope = CommandScope("session", "account")
    val row =
        CommandRecord("reply", "reply-id", "resource", "sort", "sealed-request", "sealed-state")
    val store = database.commands
    database.accounts.activate(scope.sessionId, scope.accountKey)
    store.insert(scope, row)
    assertFalse(store.archiveAbsent(scope, row, "proof"))
    assertFalse(
        store.archiveAbsent(scope, row.copy(id = "other-id", sortKey = "other-sort"), "proof")
    )
    assertEquals(row, store.get(scope, row.partition, row.id))
    assertNull(store.archived(scope, row.partition, row.id))
    for (index in 1..127) {
        val id = "id-$index"
        assertEquals(
            CommandInsertResult.Stored,
            store.insert(scope, row.copy(id = id, resourceId = id, sortKey = id)),
        )
    }
    val cancelled = row.copy(id = "cancelled", resourceId = "cancelled", sortKey = "cancelled")
    assertTrue(store.archiveAbsent(scope, cancelled, "proof"))
    assertEquals(128, store.observeAll(scope, row.partition).first().size)
    val competing = row.copy(partition = "separate")
    val archive = async { store.archiveAbsent(scope, competing, "proof") }
    val insert = async { store.insert(scope, competing) }
    assertTrue(archive.await()) // Active capacity is full; a cancellation still persists.
    assertTrue(
        insert.await() in
            setOf(CommandInsertResult.CapacityExceeded, CommandInsertResult.AlreadyStored)
    )
    assertNull(store.get(scope, competing.partition, competing.id))
}
