package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private val commandWakeScope = CommandScope("command-wake-session", "command-wake-account")
private const val commandWakePartition = "command-wake/records"
private val commandWake = WorkWakeRequest("command.recovery", "first", 2)

private fun wakeCommand(id: Int) =
    CommandRecord(
        commandWakePartition,
        "id-$id",
        "resource-$id",
        id.toString().padStart(4, '0'),
        "sealed-$id",
        "pending",
    )

private suspend fun activateCommandWake(database: AppDatabase) {
    database.accounts.activate(commandWakeScope.sessionId, commandWakeScope.accountKey)
}

private suspend fun assertCommandWakeRows(database: AppDatabase, vararg ids: Int) {
    assertEquals(
        ids.map(::wakeCommand),
        database.commands.observeAll(commandWakeScope, commandWakePartition).first(),
    )
}

suspend fun verifyCommandWakeInsertion(database: AppDatabase) {
    activateCommandWake(database)
    val store = database.commands
    assertEquals(
        CommandInsertResult.Stored,
        store.insertBatchAndWake(commandWakeScope, listOf(wakeCommand(1)), commandWake),
    )
    assertCommandWakeRows(database, 1)
    assertEquals(
        WorkWakeRecord(commandWake.key, "first", true),
        database.workWakes.get(commandWake.key),
    )
    assertTrue(store.archive(commandWakeScope, wakeCommand(1), "completed"))
    // A mixed batch retains an archived identity and publishes only the two new commands.
    assertEquals(
        CommandInsertResult.Stored,
        store.insertBatchAndWake(
            commandWakeScope,
            (1..3).map(::wakeCommand),
            commandWake.copy(generation = "second"),
        ),
    )
    assertCommandWakeRows(database, 2, 3)
    assertEquals(
        CommandArchive(wakeCommand(1), "completed"),
        store.archived(commandWakeScope, commandWakePartition, "id-1"),
    )
    assertEquals(
        WorkWakeRecord(commandWake.key, "second", true),
        database.workWakes.get(commandWake.key),
    )
}

suspend fun verifyCommandWakeReplay(database: AppDatabase) {
    activateCommandWake(database)
    val store = database.commands
    val entries = (1..2).map(::wakeCommand)
    assertEquals(
        CommandInsertResult.Stored,
        store.insertBatchAndWake(commandWakeScope, entries, commandWake),
    )
    assertTrue(store.archive(commandWakeScope, entries[0], "completed"))
    assertTrue(store.update(commandWakeScope, commandWakePartition, entries[1].id, 0, "delivered"))
    for (acknowledged in listOf(false, true)) {
        if (acknowledged)
            assertTrue(database.workWakes.acknowledge(commandWake.key, commandWake.generation))
        val retained = database.workWakes.get(commandWake.key)
        // Even another full-capacity key or a fresh generation must not create work for a replay.
        for (wake in
            listOf(
                commandWake,
                commandWake.copy(generation = "unused"),
                commandWake.copy(key = "unregistered", maxKeys = 1),
            )) {
            assertEquals(
                CommandInsertResult.AlreadyStored,
                store.insertBatchAndWake(commandWakeScope, entries, wake),
            )
            assertEquals(retained, database.workWakes.get(commandWake.key))
            assertNull(database.workWakes.get("unregistered"))
        }
    }
    val delivered = entries[1].copy(state = "delivered", revision = 1)
    assertEquals(delivered, store.get(commandWakeScope, commandWakePartition, delivered.id))
    assertTrue(store.archive(commandWakeScope, delivered, "completed-2"))
    assertEquals(
        CommandInsertResult.AlreadyStored,
        store.insertBatchAndWake(commandWakeScope, entries, commandWake.copy(generation = "unused")),
    )
    assertCommandWakeRows(database)
    assertEquals(
        WorkWakeRecord(commandWake.key, "first", false),
        database.workWakes.get(commandWake.key),
    )
}

suspend fun verifyCommandWakeGeneration(database: AppDatabase) {
    activateCommandWake(database)
    assertEquals(
        CommandInsertResult.Stored,
        database.commands.insertBatchAndWake(commandWakeScope, listOf(wakeCommand(1)), commandWake),
    )
    for (acknowledged in listOf(false, true)) {
        if (acknowledged)
            assertTrue(database.workWakes.acknowledge(commandWake.key, commandWake.generation))
        assertNull(
            database.commands.insertBatchAndWake(
                commandWakeScope,
                listOf(wakeCommand(2)),
                commandWake,
            )
        )
        assertCommandWakeRows(database, 1)
        assertEquals(
            WorkWakeRecord(commandWake.key, "first", !acknowledged),
            database.workWakes.get(commandWake.key),
        )
    }
    assertEquals(
        CommandInsertResult.Stored,
        database.commands.insertBatchAndWake(
            commandWakeScope,
            listOf(wakeCommand(2)),
            commandWake.copy(generation = "fresh"),
        ),
    )
    assertCommandWakeRows(database, 1, 2)
    assertFalse(database.workWakes.acknowledge(commandWake.key, "first"))
}

suspend fun verifyCommandWakeCapacity(database: AppDatabase) {
    activateCommandWake(database)
    database.workWakes.request(commandWake.key, "occupied", 1)
    val blocked = commandWake.copy(key = "another", maxKeys = 1)
    assertNull(
        database.commands.insertBatchAndWake(commandWakeScope, listOf(wakeCommand(1)), blocked)
    )
    assertCommandWakeRows(database)
    assertNull(database.workWakes.get(blocked.key))
    assertEquals(
        WorkWakeRecord(commandWake.key, "occupied", true),
        database.workWakes.get(commandWake.key),
    )
    // Existing keys may advance at capacity; retained commands still count against the account
    // bound.
    assertEquals(
        CommandInsertResult.Stored,
        database.commands.insertBatchAndWake(
            commandWakeScope,
            (1..128).map(::wakeCommand),
            commandWake.copy(maxKeys = 1),
        ),
    )
    assertEquals(
        CommandInsertResult.CapacityExceeded,
        database.commands.insertBatchAndWake(
            commandWakeScope,
            listOf(wakeCommand(129)),
            commandWake.copy(generation = "full"),
        ),
    )
    assertNull(database.commands.get(commandWakeScope, commandWakePartition, "id-129"))
    assertEquals(
        WorkWakeRecord(commandWake.key, "first", true),
        database.workWakes.get(commandWake.key),
    )
}

suspend fun verifyCommandWakeOwnershipAndIdentity(database: AppDatabase) {
    activateCommandWake(database)
    val store = database.commands
    assertEquals(
        CommandInsertResult.Stored,
        store.insertBatchAndWake(commandWakeScope, listOf(wakeCommand(1)), commandWake),
    )
    val next = commandWake.copy(generation = "must-not-commit")
    val resourceConflict = wakeCommand(2).copy(resourceId = wakeCommand(1).resourceId)
    assertEquals(
        CommandInsertResult.ResourceOccupied,
        store.insertBatchAndWake(commandWakeScope, listOf(wakeCommand(3), resourceConflict), next),
    )
    for (archived in listOf(false, true)) {
        if (archived) assertTrue(store.archive(commandWakeScope, wakeCommand(1), "completed"))
        assertFails {
            store.insertBatchAndWake(
                commandWakeScope,
                listOf(wakeCommand(3), wakeCommand(1).copy(request = "changed")),
                next,
            )
        }
        assertNull(store.get(commandWakeScope, commandWakePartition, "id-3"))
    }
    for (replacement in
        listOf(
            commandWakeScope.copy(sessionId = "new-session"),
            CommandScope("foreign-session", "foreign-account"),
        )) {
        database.accounts.activate(replacement.sessionId, replacement.accountKey)
        assertEquals(
            CommandInsertResult.ScopeChanged,
            store.insertBatchAndWake(commandWakeScope, listOf(wakeCommand(2)), next),
        )
        assertNull(store.get(replacement, commandWakePartition, "id-2"))
    }
    activateCommandWake(database)
    assertCommandWakeRows(database)
    assertEquals(
        CommandArchive(wakeCommand(1), "completed"),
        store.archived(commandWakeScope, commandWakePartition, "id-1"),
    )
    assertEquals(
        WorkWakeRecord(commandWake.key, "first", true),
        database.workWakes.get(commandWake.key),
    )
}

suspend fun verifyCommandWakeConstraintRollback(database: AppDatabase) {
    activateCommandWake(database)
    assertEquals(
        CommandInsertResult.Stored,
        database.commands.insertBatchAndWake(commandWakeScope, listOf(wakeCommand(1)), commandWake),
    )
    // Both resources are free and batch keys differ. The second insert hits an existing unique
    // sort key only AFTER the transaction has written its wake and the first valid command.
    val entries = listOf(wakeCommand(2), wakeCommand(3).copy(sortKey = wakeCommand(1).sortKey))
    for (wake in
        listOf(commandWake.copy(generation = "rollback"), commandWake.copy(key = "rollback"))) {
        assertFails { database.commands.insertBatchAndWake(commandWakeScope, entries, wake) }
        assertCommandWakeRows(database, 1)
        assertEquals(
            WorkWakeRecord(commandWake.key, "first", true),
            database.workWakes.get(commandWake.key),
        )
        assertNull(database.workWakes.get("rollback"))
    }
}

suspend fun verifyCommandWakeAcknowledgmentRace(database: AppDatabase) = coroutineScope {
    activateCommandWake(database)
    database.workWakes.request(commandWake.key, "original", 2)
    repeat(8) { round ->
        val old = assertNotNull(database.workWakes.get(commandWake.key))
        val next = commandWake.copy(generation = "race-$round")
        val go = CompletableDeferred<Unit>()
        val writer = async {
            go.await()
            database.commands.insertBatchAndWake(commandWakeScope, listOf(wakeCommand(round)), next)
        }
        val consumer = async {
            go.await()
            database.workWakes.acknowledge(old.key, old.generation)
        }
        go.complete(Unit)
        assertEquals(CommandInsertResult.Stored, writer.await())
        consumer.await()
        assertEquals(
            wakeCommand(round),
            database.commands.get(commandWakeScope, commandWakePartition, "id-$round"),
        )
        assertEquals(
            WorkWakeRecord(next.key, next.generation, true),
            database.workWakes.get(next.key),
        )
        assertFalse(database.workWakes.acknowledge(old.key, old.generation))
    }
}

suspend fun verifyCommandWakeConcurrentCapacity(database: AppDatabase) = coroutineScope {
    activateCommandWake(database)
    val go = CompletableDeferred<Unit>()
    val writers =
        (1..2).map { id ->
            async {
                go.await()
                database.commands.insertBatchAndWake(
                    commandWakeScope,
                    listOf(wakeCommand(id)),
                    WorkWakeRequest("race.$id", "first", 1),
                )
            }
        }
    go.complete(Unit)
    val outcomes = writers.map { it.await() }
    assertEquals(1, outcomes.count { it == CommandInsertResult.Stored })
    assertEquals(1, outcomes.count { it == null })
    for (id in 1..2) {
        val stored = outcomes[id - 1] == CommandInsertResult.Stored
        assertEquals(
            stored,
            database.commands.get(commandWakeScope, commandWakePartition, "id-$id") != null,
        )
        assertEquals(stored, database.workWakes.get("race.$id") != null)
    }
}

suspend fun verifyCommandWakeLostResponse(database: AppDatabase) {
    activateCommandWake(database)
    val actual = database.commands
    val interrupted =
        object : CommandStore by actual {
            override suspend fun insertBatchAndWake(
                scope: CommandScope,
                records: List<CommandRecord>,
                wake: WorkWakeRequest,
            ): CommandInsertResult? {
                assertEquals(
                    CommandInsertResult.Stored,
                    actual.insertBatchAndWake(scope, records, wake),
                )
                throw CancellationException("Committed response lost")
            }
        }
    assertFailsWith<CancellationException> {
        interrupted.insertBatchAndWake(commandWakeScope, listOf(wakeCommand(1)), commandWake)
    }
    assertCommandWakeRows(database, 1)
    assertTrue(database.workWakes.acknowledge(commandWake.key, commandWake.generation))
    assertEquals(
        CommandInsertResult.AlreadyStored,
        actual.insertBatchAndWake(
            commandWakeScope,
            listOf(wakeCommand(1)),
            commandWake.copy(generation = "retry"),
        ),
    )
    assertEquals(
        WorkWakeRecord(commandWake.key, "first", false),
        database.workWakes.get(commandWake.key),
    )
}

suspend fun verifyCommandWakeBounds(database: AppDatabase) {
    activateCommandWake(database)
    for (invalid in
        listOf(
            commandWake.copy(key = "identity/account"),
            commandWake.copy(generation = ""),
            commandWake.copy(maxKeys = 0),
            commandWake.copy(maxKeys = 129),
        )) {
        assertFailsWith<IllegalArgumentException> {
            database.commands.insertBatchAndWake(commandWakeScope, listOf(wakeCommand(1)), invalid)
        }
    }
    for (invalid in
        listOf(
            emptyList(),
            listOf(wakeCommand(1), wakeCommand(1)),
            (1..129).map(::wakeCommand),
            listOf(wakeCommand(1).copy(request = "")),
        )) {
        assertFailsWith<IllegalArgumentException> {
            database.commands.insertBatchAndWake(commandWakeScope, invalid, commandWake)
        }
    }
    assertCommandWakeRows(database)
    assertNull(database.workWakes.get(commandWake.key))
}

suspend fun verifyCommandWakeCancellation(database: AppDatabase) = coroutineScope {
    activateCommandWake(database)
    launch {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> {
                database.commands.insertBatchAndWake(
                    commandWakeScope,
                    listOf(wakeCommand(1)),
                    commandWake,
                )
            }
        }
        .join()
    assertCommandWakeRows(database)
    assertNull(database.workWakes.get(commandWake.key))
    val entries = (1..20).map(::wakeCommand)
    val admitted =
        launch(start = CoroutineStart.UNDISPATCHED) {
            database.commands.insertBatchAndWake(commandWakeScope, entries, commandWake)
        }
    admitted.cancelAndJoin()
    // SQLite may roll back; an admitted browser transaction finishes before releasing ownership.
    // Cancellation never permits a partial batch or a wake without the complete durable mutation.
    val rows = database.commands.observeAll(commandWakeScope, commandWakePartition).first()
    assertTrue(rows.isEmpty() || rows == entries)
    assertEquals(
        if (rows.isEmpty()) null else WorkWakeRecord(commandWake.key, "first", true),
        database.workWakes.get(commandWake.key),
    )
    assertEquals(
        if (rows.isEmpty()) CommandInsertResult.Stored else CommandInsertResult.AlreadyStored,
        database.commands.insertBatchAndWake(commandWakeScope, entries, commandWake),
    )
    assertEquals(
        entries,
        database.commands.observeAll(commandWakeScope, commandWakePartition).first(),
    )
}

suspend fun verifyCommandWakeCompatibility(database: AppDatabase) {
    activateCommandWake(database)
    database.workWakes.request(commandWake.key, commandWake.generation, 1)
    assertTrue(database.workWakes.acknowledge(commandWake.key, commandWake.generation))
    assertEquals(
        CommandInsertResult.Stored,
        database.commands.insert(commandWakeScope, wakeCommand(1)),
    )
    assertEquals(
        CommandInsertResult.Stored,
        database.commands.insertBatch(commandWakeScope, listOf(wakeCommand(1), wakeCommand(2))),
    )
    assertEquals(
        CommandInsertResult.AlreadyStored,
        database.commands.insertBatch(commandWakeScope, listOf(wakeCommand(1), wakeCommand(2))),
    )
    assertCommandWakeRows(database, 1, 2)
    assertEquals(
        WorkWakeRecord(commandWake.key, "first", false),
        database.workWakes.get(commandWake.key),
    )
}

suspend fun prepareCommandWakeRestart(database: AppDatabase) {
    activateCommandWake(database)
    assertEquals(
        CommandInsertResult.Stored,
        database.commands.insertBatchAndWake(
            commandWakeScope,
            (1..2).map(::wakeCommand),
            commandWake,
        ),
    )
    assertTrue(database.commands.archive(commandWakeScope, wakeCommand(1), "completed"))
}

suspend fun verifyCommandWakeRestart(database: AppDatabase, acknowledged: Boolean) {
    val replacement = commandWakeScope.copy(sessionId = "reopened-session")
    database.accounts.activate(replacement.sessionId, replacement.accountKey)
    assertEquals(
        listOf(wakeCommand(2)),
        database.commands.observeAll(replacement, commandWakePartition).first(),
    )
    assertEquals(
        CommandArchive(wakeCommand(1), "completed"),
        database.commands.archived(replacement, commandWakePartition, "id-1"),
    )
    assertEquals(
        WorkWakeRecord(commandWake.key, "first", !acknowledged),
        database.workWakes.get(commandWake.key),
    )
    assertEquals(
        CommandInsertResult.AlreadyStored,
        database.commands.insertBatchAndWake(
            replacement,
            (1..2).map(::wakeCommand),
            commandWake.copy(generation = "replay"),
        ),
    )
    if (!acknowledged)
        assertTrue(database.workWakes.acknowledge(commandWake.key, commandWake.generation))
    assertEquals(
        WorkWakeRecord(commandWake.key, "first", false),
        database.workWakes.get(commandWake.key),
    )
}
