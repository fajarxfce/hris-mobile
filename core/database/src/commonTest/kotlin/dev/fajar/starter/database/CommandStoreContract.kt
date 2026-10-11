package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private const val partition = "company-a/inbox"

private fun command(id: String = "001") =
    CommandRecord(partition, id, "resource-" + id, id, "sealed-request-" + id, "sealed-pending")

suspend fun verifyCommandAtomicity(database: AppDatabase) = coroutineScope {
    val scope = CommandScope("atomic-session", "atomic-account")
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val store = database.commands
    val original = command()
    assertFalse(store.containsRecords())
    assertEquals(CommandInsertResult.Stored, store.insert(scope, original))
    assertEquals(CommandInsertResult.AlreadyStored, store.insert(scope, original))
    assertEquals(
        CommandInsertResult.ResourceOccupied,
        store.insert(scope, original.copy(id = "other", sortKey = "other")),
    )
    assertFails { store.insert(scope, original.copy(request = "replaced-request")) }
    assertFails { store.insert(scope, original.copy(id = "collision", resourceId = "other")) }
    assertEquals(original, store.get(scope, partition, original.id))
    val writes =
        (1..2).map { value ->
            async { store.update(scope, partition, original.id, 0, "sealed-outcome-" + value) }
        }
    assertEquals(1, writes.count { it.await() })
    val updated = assertNotNull(store.get(scope, partition, original.id))
    assertEquals(1, updated.revision)
    assertEquals(original.request, updated.request)
    assertTrue(updated.state.startsWith("sealed-outcome-"))
    assertEquals(CommandInsertResult.AlreadyStored, store.insert(scope, original))
    assertEquals(updated, store.get(scope, partition, original.id))
    assertFalse(store.update(scope, partition, original.id, 0, "late"))
    assertFalse(store.remove(scope, partition, original.id, 0))
    assertTrue(store.remove(scope, partition, original.id, 1))
    assertFalse(store.containsRecords())
    assertTrue(store.observe(scope, partition).first().isEmpty())
}

suspend fun verifyCommandOwnership(database: AppDatabase) = coroutineScope {
    val a = CommandScope("first-session", "account-a")
    val store = database.commands
    val original = command()
    assertEquals(CommandInsertResult.ScopeChanged, store.insert(a, original))
    database.accounts.activate(a.sessionId, a.accountKey)
    assertEquals(CommandInsertResult.Stored, store.insert(a, original))
    val otherCompany = original.copy(partition = "company-b/inbox", request = "sealed-company-b")
    assertEquals(CommandInsertResult.Stored, store.insert(a, otherCompany))
    val cleared =
        async(start = CoroutineStart.UNDISPATCHED) {
            store.observe(a, partition).first { it.isEmpty() }
        }
    database.accounts.activate(null)
    assertTrue(cleared.await().isEmpty())
    assertTrue(store.containsRecords())
    assertNull(store.get(a, partition, original.id))
    assertFalse(store.remove(a, partition, original.id, 0))
    val b = CommandScope("second-session", "account-b")
    database.accounts.activate(b.sessionId, b.accountKey)
    assertNull(store.get(b, partition, original.id))
    assertNull(store.get(b.copy(accountKey = a.accountKey), partition, original.id))
    assertFalse(store.update(a, partition, original.id, 0, "late-account-write"))
    assertEquals(
        CommandInsertResult.Stored,
        store.insert(b, original.copy(request = "sealed-account-b")),
    )
    val resumed = a.copy(sessionId = "new-login")
    database.accounts.activate(resumed.sessionId, resumed.accountKey)
    assertEquals(original, store.get(resumed, partition, original.id))
    assertEquals(otherCompany, store.get(resumed, otherCompany.partition, original.id))
    assertEquals(CommandInsertResult.ScopeChanged, store.insert(a, command("late")))
    assertNull(store.get(a, partition, original.id))
    database.accounts.activate(resumed.sessionId) // Unbound legacy sessions cannot access commands.
    assertNull(store.get(resumed, partition, original.id))
}

suspend fun verifyCommandBounds(database: AppDatabase) {
    val scope = CommandScope("bounded-session", "bounded-account")
    val store = database.commands
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val original = command()
    assertFailsWith<IllegalArgumentException> {
        store.insert(scope, original.copy(request = "x".repeat(131_073)))
    }
    assertFailsWith<IllegalArgumentException> {
        store.insert(scope, original.copy(state = "\u00e9".repeat(16_385)))
    }
    assertFailsWith<IllegalArgumentException> { store.insert(scope, original.copy(revision = 1)) }
    assertFalse(store.containsRecords())
    val entries = (1..128).map { command(it.toString().padStart(3, '0')) }
    for (entry in entries) assertEquals(CommandInsertResult.Stored, store.insert(scope, entry))
    assertEquals(CommandInsertResult.CapacityExceeded, store.insert(scope, command("129")))
    val first = store.observe(scope, partition).first()
    val second = store.observe(scope, partition, after = first.last().sortKey).first()
    val third = store.observe(scope, partition, after = second.last().sortKey).first()
    assertEquals(entries, first + second + third)
    assertEquals(
        listOf(entries[7]),
        store.observe(scope, partition, resourceId = entries[7].resourceId).first(),
    )
    assertFailsWith<IllegalArgumentException> {
        store.observe(scope, partition, limit = 51).first()
    }
    assertFailsWith<IllegalArgumentException> {
        store.update(scope, partition, original.id, 9_007_199_254_740_991L, "overflow")
    }
    assertFailsWith<IllegalArgumentException> {
        store.update(scope, partition, original.id, 0, "x".repeat(32_769))
    }
    assertEquals(original, store.get(scope, partition, original.id))
    for (index in 2..4) {
        val next = CommandScope("session-" + index, "account-" + index)
        database.accounts.activate(next.sessionId, next.accountKey)
        for (entry in entries) assertEquals(CommandInsertResult.Stored, store.insert(next, entry))
    }
    val full = CommandScope("fifth-session", "fifth-account")
    database.accounts.activate(full.sessionId, full.accountKey)
    assertEquals(CommandInsertResult.CapacityExceeded, store.insert(full, original))
}

suspend fun verifyCommandCancellation(database: AppDatabase) = coroutineScope {
    val scope = CommandScope("cancelled-session", "cancelled-account")
    val store = database.commands
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val original = command()
    val creation = launch(start = CoroutineStart.UNDISPATCHED) { store.insert(scope, original) }
    yield()
    creation.cancelAndJoin()
    val stored = store.get(scope, partition, original.id)
    assertTrue(stored == null || stored == original)
    if (stored == null) assertEquals(CommandInsertResult.Stored, store.insert(scope, original))
    val change =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.update(scope, partition, original.id, 0, "sealed-complete")
        }
    yield()
    change.cancelAndJoin()
    val result = assertNotNull(store.get(scope, partition, original.id))
    assertTrue(
        result == original || result == original.copy(revision = 1, state = "sealed-complete")
    )
    assertEquals(listOf(result), store.observe(scope, partition).first())
    assertFalse(
        store.insert(scope.copy(sessionId = "stale"), original) == CommandInsertResult.Stored
    )
}
