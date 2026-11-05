package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private val batchScope = CommandScope("batch-session", "batch-account")
private const val batchPartition = "company/messages"

internal fun batchCommand(index: Int) =
    CommandRecord(
        batchPartition,
        "id-$index",
        "resource-$index",
        "order-${index.toString().padStart(3, '0')}",
        "sealed-$index",
        "queued",
    )

suspend fun verifyBatchReplayAndArchives(db: AppDatabase) {
    db.accounts.activate(batchScope.sessionId, batchScope.accountKey)
    val store = db.commands
    val entries = (1..3).map(::batchCommand)
    assertEquals(CommandInsertResult.Stored, store.insertBatch(batchScope, entries))
    assertEquals(entries, store.observeAll(batchScope, batchPartition).first())
    assertTrue(store.update(batchScope, batchPartition, entries[0].id, 0, "sent"))
    val sent = assertNotNull(store.get(batchScope, batchPartition, entries[0].id))
    assertTrue(store.archive(batchScope, sent, "receipt"))
    assertEquals(CommandInsertResult.AlreadyStored, store.insertBatch(batchScope, entries))
    assertEquals(
        CommandArchive(sent, "receipt"),
        store.archived(batchScope, batchPartition, sent.id),
    )
    assertNull(store.get(batchScope, batchPartition, sent.id))
    val next = batchCommand(4)
    assertEquals(CommandInsertResult.Stored, store.insertBatch(batchScope, entries + next))
    assertEquals(entries.drop(1) + next, store.observeAll(batchScope, batchPartition).first())
    assertEquals(CommandInsertResult.AlreadyStored, store.insertBatch(batchScope, entries + next))
}

/** Called on a different connection after verifyBatchReplayAndArchives and a complete close. */
suspend fun verifyBatchAfterReopen(db: AppDatabase) {
    val renewed = batchScope.copy(sessionId = "batch-renewed-session")
    db.accounts.activate(renewed.sessionId, renewed.accountKey)
    val entries = (1..4).map(::batchCommand)
    assertEquals(CommandInsertResult.ScopeChanged, db.commands.insertBatch(batchScope, entries))
    assertEquals(CommandInsertResult.AlreadyStored, db.commands.insertBatch(renewed, entries))
    assertEquals(entries.drop(1), db.commands.observeAll(renewed, batchPartition).first())
    assertEquals(
        CommandArchive(entries.first().copy(state = "sent", revision = 1), "receipt"),
        db.commands.archived(renewed, batchPartition, entries.first().id),
    )
    assertNull(db.commands.get(renewed, batchPartition, entries.first().id))
}

suspend fun verifyBatchConflictRollsBack(db: AppDatabase) {
    db.accounts.activate(batchScope.sessionId, batchScope.accountKey)
    val store = db.commands
    val original = batchCommand(1)
    assertEquals(CommandInsertResult.Stored, store.insert(batchScope, original))
    val next = batchCommand(2)
    assertEquals(
        CommandInsertResult.ResourceOccupied,
        store.insertBatch(
            batchScope,
            listOf(next, batchCommand(3).copy(resourceId = original.resourceId)),
        ),
    )
    assertNull(store.get(batchScope, batchPartition, next.id))
    assertFails { store.insertBatch(batchScope, listOf(next, original.copy(request = "replaced"))) }
    assertNull(store.get(batchScope, batchPartition, next.id))
    // The unique sort index fails only at the final write. Earlier writes must roll back too.
    assertFails {
        store.insertBatch(
            batchScope,
            listOf(next, batchCommand(3).copy(sortKey = original.sortKey)),
        )
    }
    assertEquals(listOf(original), store.observeAll(batchScope, batchPartition).first())
    assertTrue(store.archive(batchScope, original, "receipt"))
    assertFails { store.insertBatch(batchScope, listOf(next, original.copy(request = "replaced"))) }
    assertTrue(store.observeAll(batchScope, batchPartition).first().isEmpty())
}

suspend fun verifyBatchOwnershipAndBounds(db: AppDatabase) {
    val entries = listOf(batchCommand(1), batchCommand(2))
    val store = db.commands
    assertEquals(CommandInsertResult.ScopeChanged, store.insertBatch(batchScope, entries))
    db.accounts.activate(batchScope.sessionId, batchScope.accountKey)
    assertFailsWith<IllegalArgumentException> { store.insertBatch(batchScope, emptyList()) }
    assertFailsWith<IllegalArgumentException> {
        store.insertBatch(batchScope, (1..129).map(::batchCommand))
    }
    assertFailsWith<IllegalArgumentException> {
        store.insertBatch(batchScope, listOf(entries[0], entries[0]))
    }
    assertFailsWith<IllegalArgumentException> {
        store.insertBatch(
            batchScope,
            listOf(entries[0], entries[1].copy(resourceId = entries[0].resourceId)),
        )
    }
    assertFailsWith<IllegalArgumentException> {
        store.insertBatch(
            batchScope,
            listOf(entries[0], entries[1].copy(sortKey = entries[0].sortKey)),
        )
    }
    assertFailsWith<IllegalArgumentException> {
        store.insertBatch(
            batchScope,
            listOf(entries[0], entries[1].copy(request = "x".repeat(131_073))),
        )
    }
    assertFalse(store.containsRecords())
    assertEquals(CommandInsertResult.Stored, store.insertBatch(batchScope, entries))
    val next = batchScope.copy(sessionId = "new-login")
    db.accounts.activate(next.sessionId, next.accountKey)
    assertEquals(
        CommandInsertResult.ScopeChanged,
        store.insertBatch(batchScope, listOf(batchCommand(3))),
    )
    assertEquals(CommandInsertResult.AlreadyStored, store.insertBatch(next, entries))
    val other = CommandScope("other-session", "other-account")
    db.accounts.activate(other.sessionId, other.accountKey)
    assertTrue(store.observeAll(other, batchPartition).first().isEmpty())
    assertEquals(
        CommandInsertResult.Stored,
        store.insertBatch(other, entries.map { it.copy(request = "other") }),
    )
    db.accounts.activate(next.sessionId, next.accountKey)
    assertEquals(entries, store.observeAll(next, batchPartition).first())
}

suspend fun verifyBatchCapacity(db: AppDatabase) {
    val store = db.commands
    db.accounts.activate(batchScope.sessionId, batchScope.accountKey)
    val entries = (1..127).map(::batchCommand)
    assertEquals(CommandInsertResult.Stored, store.insertBatch(batchScope, entries))
    assertEquals(
        CommandInsertResult.CapacityExceeded,
        store.insertBatch(batchScope, listOf(batchCommand(128), batchCommand(129))),
    )
    assertEquals(entries, store.observeAll(batchScope, batchPartition).first())
    assertEquals(
        CommandInsertResult.Stored,
        store.insertBatch(batchScope, listOf(entries[0], batchCommand(128))),
    )
    assertEquals(CommandInsertResult.AlreadyStored, store.insertBatch(batchScope, entries))
    for (index in 2..4) {
        val scope = CommandScope("session-$index", "account-$index")
        db.accounts.activate(scope.sessionId, scope.accountKey)
        assertEquals(
            CommandInsertResult.Stored,
            store.insertBatch(scope, (1..128).map(::batchCommand)),
        )
    }
    val full = CommandScope("full-session", "full-account")
    db.accounts.activate(full.sessionId, full.accountKey)
    assertEquals(
        CommandInsertResult.CapacityExceeded,
        store.insertBatch(full, listOf(batchCommand(1), batchCommand(2))),
    )
    assertTrue(store.observeAll(full, batchPartition).first().isEmpty())
}

suspend fun verifyBatchRacesAndCancellation(db: AppDatabase) = coroutineScope {
    db.accounts.activate(batchScope.sessionId, batchScope.accountKey)
    val store = db.commands
    val entries = (1..20).map(::batchCommand)
    val writes = (1..2).map { async { store.insertBatch(batchScope, entries) } }.awaitAll()
    assertEquals(1, writes.count { it == CommandInsertResult.Stored })
    assertEquals(1, writes.count { it == CommandInsertResult.AlreadyStored })
    val cancelled = (21..40).map(::batchCommand)
    val job =
        launch(start = CoroutineStart.UNDISPATCHED) { store.insertBatch(batchScope, cancelled) }
    yield()
    job.cancelAndJoin()
    val stored = store.observeAll(batchScope, batchPartition).first()
    assertTrue(stored == entries || stored == entries + cancelled)
    val outcome = store.insertBatch(batchScope, cancelled)
    assertEquals(
        if (stored == entries) CommandInsertResult.Stored else CommandInsertResult.AlreadyStored,
        outcome,
    )
    assertEquals(entries + cancelled, store.observeAll(batchScope, batchPartition).first())
    val conflicting = listOf(batchCommand(41), batchCommand(42).copy(resourceId = "competing"))
    val standalone = batchCommand(43).copy(resourceId = "competing")
    val race =
        listOf(
                async { store.insertBatch(batchScope, conflicting) },
                async { store.insert(batchScope, standalone) },
            )
            .awaitAll()
    assertEquals(1, race.count { it == CommandInsertResult.Stored })
    assertEquals(1, race.count { it == CommandInsertResult.ResourceOccupied })
    assertEquals(
        race[0] == CommandInsertResult.Stored,
        store.get(batchScope, batchPartition, conflicting[0].id) != null,
    )
}
