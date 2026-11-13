package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach

internal class InsertMutationFixture(val database: AppDatabase) {
    val scope = CommandScope("insert-session", "insert-account")
    val replicaScope = ReplicaScope(scope.sessionId, scope.accountKey)
    val command = CommandRecord("edits", "operation", "operation", "001", "sealed-intent", "queued")
    val source = "retained/source"
    val target = "inbox/projection"
    val document = ReplicaDocument("item", 1, "001", "original", setOf("visible"))
    val wake = WorkWakeRequest("insert.recovery", "edit", 2)
    lateinit var before: List<ReplicaCheckpoint>

    suspend fun seed() {
        database.accounts.activate(scope.sessionId, scope.accountKey)
        assertTrue(
            database.replicas.mutate(
                replicaScope,
                listOf(source, target).map {
                    ReplicaMutation.Replace(
                        it,
                        null,
                        ReplicaSnapshot("initial", "initial", listOf(document)),
                    )
                },
            )
        )
        before =
            listOf(source, target).map {
                assertNotNull(database.replicas.checkpoint(replicaScope, it))
            }
    }

    fun write(label: String = "edited", expected: ReplicaCheckpoint = before[1]) =
        ReplicaMutation.Commit(
            expected.handle,
            expected.revision,
            label,
            listOf(document.copy(version = expected.revision + 2, payload = label)),
            true,
        )

    fun mutations(label: String = "edited") =
        listOf(ReplicaMutation.Check(source, before[0]), write(label))

    suspend fun insert(
        record: CommandRecord = command,
        changes: List<ReplicaMutation> = mutations(),
        request: WorkWakeRequest = wake,
    ) = database.commands.insertAndMutate(scope, record, changes, request)

    suspend fun sourceUnchanged() {
        assertEquals(before[0], database.replicas.checkpoint(replicaScope, source))
        assertEquals(document, database.replicas.document(replicaScope, source, document.id))
    }

    suspend fun unchanged(expectedWake: WorkWakeRecord? = null) {
        sourceUnchanged()
        assertNull(database.commands.get(scope, command.partition, command.id))
        assertNull(database.commands.archived(scope, command.partition, command.id))
        assertEquals(before[1], database.replicas.checkpoint(replicaScope, target))
        assertEquals(document, database.replicas.document(replicaScope, target, document.id))
        assertEquals(expectedWake, database.workWakes.get(wake.key))
    }

    suspend fun committed(label: String = "edited", record: CommandRecord = command) {
        sourceUnchanged()
        assertEquals(record, database.commands.get(scope, record.partition, record.id))
        assertEquals(label, database.replicas.checkpoint(replicaScope, target)?.payload)
        assertEquals(label, database.replicas.document(replicaScope, target, document.id)?.payload)
        assertEquals(
            WorkWakeRecord(wake.key, wake.generation, true),
            database.workWakes.get(wake.key),
        )
    }
}

suspend fun verifyInsertMutationCommit(db: AppDatabase) = coroutineScope {
    val f = InsertMutationFixture(db)
    f.seed()
    val commandReady = CompletableDeferred<Unit>()
    val projectionReady = CompletableDeferred<Unit>()
    val command = async {
        db.commands
            .observeAll(f.scope, f.command.partition)
            .onEach { commandReady.complete(Unit) }
            .first { it.isNotEmpty() }
    }
    val projection = async {
        db.replicas
            .observe(f.replicaScope, f.target)
            .onEach { projectionReady.complete(Unit) }
            .first { it.documents.singleOrNull()?.payload == "edited" }
    }
    commandReady.await()
    projectionReady.await()
    assertEquals(CommandInsertResult.Stored, f.insert())
    assertEquals(listOf(f.command), command.await())
    assertEquals("edited", projection.await().documents.single().payload)
    f.committed()
}

suspend fun verifyInsertMutationConditions(db: AppDatabase) {
    val f = InsertMutationFixture(db)
    f.seed()
    val stale = ReplicaMutation.Check(f.source, f.before[0].copy(revision = 1))
    val changes =
        listOf(
            listOf(stale, f.write()),
            listOf(f.write(), stale),
            listOf(
                ReplicaMutation.Check(f.source, f.before[0]),
                f.write(expected = f.before[1].copy(revision = 1)),
            ),
            listOf(ReplicaMutation.Check(f.source, null), f.write()),
            listOf(
                ReplicaMutation.Check(f.source, f.before[0].copy(payload = "different")),
                f.write(),
            ),
            listOf(ReplicaMutation.Check(f.source, f.before[0].copy(published = false)), f.write()),
        )
    for (mutations in changes) {
        assertNull(f.insert(changes = mutations))
        f.unchanged()
    }
    assertEquals(CommandInsertResult.Stored, f.insert())
    f.committed()
}

suspend fun verifyInsertMutationWakeConflicts(db: AppDatabase) {
    val f = InsertMutationFixture(db)
    f.seed()
    assertNotNull(db.workWakes.request(f.wake.key, f.wake.generation, f.wake.maxKeys))
    assertNull(f.insert())
    f.unchanged(WorkWakeRecord(f.wake.key, f.wake.generation, true))
    assertTrue(db.workWakes.acknowledge(f.wake.key, f.wake.generation))
    assertNull(f.insert())
    f.unchanged(WorkWakeRecord(f.wake.key, f.wake.generation, false))
    assertNull(f.insert(request = f.wake.copy(key = "other.recovery", maxKeys = 1)))
    assertNull(db.workWakes.get("other.recovery"))
    f.unchanged(WorkWakeRecord(f.wake.key, f.wake.generation, false))
    assertEquals(CommandInsertResult.Stored, f.insert(request = f.wake.copy(generation = "new")))
    assertEquals(WorkWakeRecord(f.wake.key, "new", true), db.workWakes.get(f.wake.key))
}

suspend fun verifyInsertMutationReplay(db: AppDatabase) {
    val f = InsertMutationFixture(db)
    f.seed()
    assertEquals(CommandInsertResult.Stored, f.insert())
    assertTrue(db.commands.update(f.scope, f.command.partition, f.command.id, 0, "sent"))
    val sent = assertNotNull(db.commands.get(f.scope, f.command.partition, f.command.id))
    assertTrue(db.workWakes.acknowledge(f.wake.key, f.wake.generation))
    val before = assertNotNull(db.replicas.checkpoint(f.replicaScope, f.target))
    assertTrue(db.replicas.mutate(f.replicaScope, listOf(f.write("later", before))))
    assertTrue(db.replicas.commit(f.before[0].handle, 0, "source-advanced", emptyList(), true))
    val current = db.replicas.checkpoint(f.replicaScope, f.target)
    repeat(2) {
        assertEquals(
            CommandInsertResult.AlreadyStored,
            f.insert(request = f.wake.copy(generation = "must-not-rewake")),
        )
        assertEquals(current, db.replicas.checkpoint(f.replicaScope, f.target))
        assertEquals(
            WorkWakeRecord(f.wake.key, f.wake.generation, false),
            db.workWakes.get(f.wake.key),
        )
        if (it == 0) {
            assertEquals(sent, db.commands.get(f.scope, f.command.partition, f.command.id))
            assertTrue(db.commands.archive(f.scope, sent, "proof"))
        }
    }
    assertNull(db.commands.get(f.scope, f.command.partition, f.command.id))
    assertEquals(
        CommandArchive(sent, "proof"),
        db.commands.archived(f.scope, f.command.partition, f.command.id),
    )
}

suspend fun verifyInsertMutationCommandConflicts(db: AppDatabase) {
    val f = InsertMutationFixture(db)
    f.seed()
    val existing = f.command.copy(id = "retained")
    assertEquals(CommandInsertResult.Stored, db.commands.insert(f.scope, existing))
    assertEquals(CommandInsertResult.ResourceOccupied, f.insert())
    f.unchanged()
    for (changed in
        listOf(
            existing.copy(request = "other"),
            existing.copy(sortKey = "002"),
            existing.copy(resourceId = "other"),
        )) {
        assertFails { f.insert(changed) }
        f.unchanged()
    }
    assertTrue(db.commands.archive(f.scope, existing, "proof"))
    assertFails { f.insert(existing.copy(request = "other")) }
    f.unchanged()
}

suspend fun verifyInsertMutationBounds(db: AppDatabase) {
    val f = InsertMutationFixture(db)
    f.seed()
    repeat(2) {
        for (changes in
            listOf(
                emptyList(),
                listOf(ReplicaMutation.Check(f.source, f.before[0])),
                listOf(f.write(), f.write()),
                f.mutations() + f.write(),
                listOf(
                    f.write(
                        expected =
                            f.before[1].copy(
                                handle =
                                    f.before[1]
                                        .handle
                                        .copy(scope = f.replicaScope.copy(accountKey = "foreign"))
                            )
                    )
                ),
            )) {
            assertFailsWith<IllegalArgumentException> { f.insert(changes = changes) }
        }
        assertFailsWith<IllegalArgumentException> { f.insert(f.command.copy(revision = 1)) }
        assertFailsWith<IllegalArgumentException> { f.insert(request = f.wake.copy(maxKeys = 0)) }
        if (it == 0) {
            f.unchanged()
            assertEquals(CommandInsertResult.Stored, f.insert())
        } else f.committed()
    }
}

suspend fun verifyInsertMutationCapacity(db: AppDatabase) {
    val f = InsertMutationFixture(db)
    f.seed()
    val entries =
        (1..128).map {
            f.command.copy(id = "filler-$it", resourceId = "filler-$it", sortKey = "filler-$it")
        }
    assertEquals(CommandInsertResult.Stored, db.commands.insertBatch(f.scope, entries))
    assertEquals(CommandInsertResult.CapacityExceeded, f.insert())
    f.unchanged()
    assertTrue(db.commands.archive(f.scope, entries.first(), "freed"))
    assertEquals(CommandInsertResult.Stored, f.insert())
    f.committed()
    for (i in 1..3) {
        val scope = CommandScope("session-$i", "account-$i")
        db.accounts.activate(scope.sessionId, scope.accountKey)
        assertEquals(CommandInsertResult.Stored, db.commands.insertBatch(scope, entries))
    }
    val other = f.scope.copy(sessionId = "last-session", accountKey = "last-account")
    db.accounts.activate(other.sessionId, other.accountKey)
    assertEquals(
        CommandInsertResult.CapacityExceeded,
        db.commands.insertAndMutate(
            other,
            f.command,
            listOf(
                ReplicaMutation.Replace(
                    f.target,
                    null,
                    ReplicaSnapshot("empty-owner", "new", listOf(f.document)),
                )
            ),
            f.wake.copy(generation = "other"),
        ),
    )
    assertNull(db.replicas.checkpoint(ReplicaScope(other.sessionId, other.accountKey), f.target))
    assertEquals(WorkWakeRecord(f.wake.key, f.wake.generation, true), db.workWakes.get(f.wake.key))
}

suspend fun verifyInsertMutationConstraints(db: AppDatabase) {
    val f = InsertMutationFixture(db)
    f.seed()
    val colliding =
        listOf(ReplicaDocument("a", 2, "same", "first"), ReplicaDocument("b", 2, "same", "second"))
    for (write in
        listOf<ReplicaMutation>(
            f.write().copy(documents = colliding),
            ReplicaMutation.Replace(
                f.target,
                f.before[1],
                ReplicaSnapshot("conflict", "bad", colliding),
            ),
        )) {
        assertFails {
            f.insert(changes = listOf(ReplicaMutation.Check(f.source, f.before[0]), write))
        }
        f.unchanged()
        assertNull(db.replicas.document(f.replicaScope, f.target, "a"))
    }
    val occupiedSort = f.command.copy(id = "other", resourceId = "other")
    assertEquals(CommandInsertResult.Stored, db.commands.insert(f.scope, occupiedSort))
    assertFails { f.insert() }
    f.unchanged()
    assertEquals(occupiedSort, db.commands.get(f.scope, occupiedSort.partition, occupiedSort.id))
}

suspend fun verifyInsertMutationOwnership(db: AppDatabase) {
    val f = InsertMutationFixture(db)
    f.seed()
    db.accounts.activate("other-session", "other-account")
    assertEquals(CommandInsertResult.ScopeChanged, f.insert())
    db.accounts.activate(f.scope.sessionId, f.scope.accountKey)
    f.unchanged()
    val renewed = f.scope.copy(sessionId = "renewed")
    db.accounts.activate(renewed.sessionId, renewed.accountKey)
    assertEquals(CommandInsertResult.ScopeChanged, f.insert())
    val scope = ReplicaScope(renewed.sessionId, renewed.accountKey)
    val source = assertNotNull(db.replicas.checkpoint(scope, f.source))
    val target = assertNotNull(db.replicas.checkpoint(scope, f.target))
    assertEquals(
        CommandInsertResult.Stored,
        db.commands.insertAndMutate(
            renewed,
            f.command,
            listOf(ReplicaMutation.Check(f.source, source), f.write(expected = target)),
            f.wake,
        ),
    )
    assertEquals(f.command, db.commands.get(renewed, f.command.partition, f.command.id))
}

suspend fun verifyInsertMutationConcurrentWriters(db: AppDatabase) = coroutineScope {
    val f = InsertMutationFixture(db)
    f.seed()
    val records =
        (1..2).map {
            f.command.copy(id = "edit-$it", resourceId = "edit-$it", sortKey = "edit-$it")
        }
    val results =
        records.map { record -> async { f.insert(record, f.mutations(record.id)) } }.awaitAll()
    assertEquals(1, results.count { it == CommandInsertResult.Stored })
    assertEquals(1, results.count { it == null })
    val winner = records[results.indexOf(CommandInsertResult.Stored)]
    f.committed(winner.id, winner)
    val loser = records[results.indexOf(null)]
    assertNull(db.commands.get(f.scope, loser.partition, loser.id))
}

suspend fun verifyInsertMutationConcurrentReplay(db: AppDatabase) = coroutineScope {
    val f = InsertMutationFixture(db)
    f.seed()
    val results = List(2) { async { f.insert() } }.awaitAll()
    assertEquals(
        setOf(CommandInsertResult.Stored, CommandInsertResult.AlreadyStored),
        results.toSet(),
    )
    f.committed()
    assertEquals(1L, db.replicas.checkpoint(f.replicaScope, f.target)?.revision)
}

suspend fun verifyInsertMutationFrozenInput(db: AppDatabase) = coroutineScope {
    val f = InsertMutationFixture(db)
    f.seed()
    val tags = mutableSetOf("captured")
    val documents = mutableListOf(f.document.copy(version = 2, payload = "edited", tags = tags))
    val changes =
        mutableListOf<ReplicaMutation>(
            ReplicaMutation.Check(f.source, f.before[0]),
            f.write().copy(documents = documents),
        )
    val result = async(start = CoroutineStart.UNDISPATCHED) { f.insert(changes = changes) }
    tags.clear()
    documents.clear()
    changes.clear()
    assertEquals(CommandInsertResult.Stored, result.await())
    f.committed()
    assertEquals(
        setOf("captured"),
        db.replicas.document(f.replicaScope, f.target, f.document.id)?.tags,
    )
}

suspend fun verifyInsertMutationCancellation(db: AppDatabase) = coroutineScope {
    val f = InsertMutationFixture(db)
    f.seed()
    launch {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> { f.insert() }
        }
        .join()
    f.unchanged()
    val writer = launch(start = CoroutineStart.UNDISPATCHED) { f.insert() }
    writer.cancelAndJoin()
    if (db.commands.get(f.scope, f.command.partition, f.command.id) == null) f.unchanged()
    else f.committed()
}

suspend fun verifyInsertMutationLostResponse(db: AppDatabase) {
    val f = InsertMutationFixture(db)
    f.seed()
    assertFailsWith<CancellationException> {
        assertEquals(CommandInsertResult.Stored, f.insert())
        throw CancellationException("caller disposed after commit")
    }
    f.committed()
    assertTrue(db.workWakes.acknowledge(f.wake.key, f.wake.generation))
    assertEquals(CommandInsertResult.AlreadyStored, f.insert())
    assertEquals(WorkWakeRecord(f.wake.key, f.wake.generation, false), db.workWakes.get(f.wake.key))
    assertEquals(f.command, db.commands.get(f.scope, f.command.partition, f.command.id))
}

suspend fun prepareInsertMutationRestart(db: AppDatabase) {
    val f = InsertMutationFixture(db)
    f.seed()
    assertEquals(CommandInsertResult.Stored, f.insert())
    f.committed()
}

suspend fun verifyInsertMutationRestart(db: AppDatabase) {
    val f = InsertMutationFixture(db)
    assertEquals(f.command, db.commands.get(f.scope, f.command.partition, f.command.id))
    assertEquals("edited", db.replicas.document(f.replicaScope, f.target, f.document.id)?.payload)
    assertEquals("original", db.replicas.document(f.replicaScope, f.source, f.document.id)?.payload)
    val current = assertNotNull(db.replicas.checkpoint(f.replicaScope, f.target))
    assertTrue(db.workWakes.acknowledge(f.wake.key, f.wake.generation))
    assertEquals(
        CommandInsertResult.AlreadyStored,
        f.insert(changes = listOf(f.write("must-not-overwrite", current))),
    )
    assertEquals(current, db.replicas.checkpoint(f.replicaScope, f.target))
    assertEquals(WorkWakeRecord(f.wake.key, f.wake.generation, false), db.workWakes.get(f.wake.key))
}
