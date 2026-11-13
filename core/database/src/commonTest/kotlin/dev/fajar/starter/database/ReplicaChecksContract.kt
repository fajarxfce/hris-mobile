package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach

private class ReplicaCheckFixture(val database: AppDatabase) {
    val scope = ReplicaScope("check-session", "check-account")
    val source = "checked/source"
    val target = "checked/projection"
    val document = ReplicaDocument("item", 1, "01", "sealed-source", setOf("visible"))
    val wake = WorkWakeRequest("checked.recovery", "initial", 2)
    lateinit var before: List<ReplicaCheckpoint>

    suspend fun seed() {
        database.accounts.activate(scope.sessionId, scope.accountKey)
        assertTrue(
            database.replicas.mutate(
                scope,
                listOf(source, target).map {
                    ReplicaMutation.Replace(
                        it,
                        null,
                        ReplicaSnapshot("initial", "initial", listOf(document)),
                    )
                },
                wake,
            )
        )
        before =
            listOf(source, target).map { assertNotNull(database.replicas.checkpoint(scope, it)) }
    }

    fun guard(expected: ReplicaCheckpoint? = before[0]) = ReplicaMutation.Check(source, expected)

    fun write(label: String, expected: ReplicaCheckpoint = before[1]) =
        ReplicaMutation.Commit(
            expected.handle,
            expected.revision,
            label,
            listOf(document.copy(version = expected.revision + 2, payload = label)),
            true,
        )

    suspend fun sourceUnchanged() {
        assertEquals(before[0], database.replicas.checkpoint(scope, source))
        assertEquals(listOf(document), database.replicas.observe(scope, source).first().documents)
    }

    suspend fun unchanged() {
        sourceUnchanged()
        assertEquals(before[1], database.replicas.checkpoint(scope, target))
        assertEquals(listOf(document), database.replicas.observe(scope, target).first().documents)
        assertEquals(
            WorkWakeRecord(wake.key, wake.generation, true),
            database.workWakes.get(wake.key),
        )
    }
}

suspend fun verifyReplicaCheckPublication(database: AppDatabase) = coroutineScope {
    val f = ReplicaCheckFixture(database)
    f.seed()
    val observing = CompletableDeferred<Unit>()
    val published = async {
        database.replicas
            .observe(f.scope, f.target)
            .onEach { observing.complete(Unit) }
            .first { it.documents.singleOrNull()?.payload == "published" }
    }
    observing.await()
    assertTrue(
        database.replicas.mutate(
            f.scope,
            listOf(f.guard(), f.write("published")),
            f.wake.copy(generation = "published"),
        )
    )
    assertEquals("published", published.await().documents.single().payload)
    f.sourceUnchanged()
    assertEquals(WorkWakeRecord(f.wake.key, "published", true), database.workWakes.get(f.wake.key))
}

suspend fun verifyReplicaCheckAbsence(database: AppDatabase) {
    val f = ReplicaCheckFixture(database)
    f.seed()
    assertFalse(database.replicas.mutate(f.scope, listOf(f.guard(null), f.write("absent"))))
    f.unchanged()
    assertTrue(database.replicas.replace(f.scope, f.source, f.before[0], null))
    val replacement =
        ReplicaMutation.Replace(
            f.target,
            f.before[1],
            ReplicaSnapshot(
                "absent-projection",
                "absent",
                listOf(f.document.copy(version = 2, payload = "absent")),
            ),
        )
    assertTrue(database.replicas.mutate(f.scope, listOf(f.guard(null), replacement)))
    assertEquals("absent", database.replicas.document(f.scope, f.target, "item")?.payload)
    assertNull(database.replicas.checkpoint(f.scope, f.source))
    assertFalse(database.replicas.containsRecords(f.source))
    val destination = assertNotNull(database.replicas.checkpoint(f.scope, f.target))
    assertTrue(
        database.replicas.replace(
            f.scope,
            f.source,
            null,
            ReplicaSnapshot("created", "created", listOf(f.document)),
        )
    )
    assertFalse(
        database.replicas.mutate(f.scope, listOf(f.guard(null), f.write("stale", destination)))
    )
    assertEquals(destination, database.replicas.checkpoint(f.scope, f.target))
}

suspend fun verifyReplicaCheckPreflightOrdering(database: AppDatabase) {
    val f = ReplicaCheckFixture(database)
    f.seed()
    val stale = f.guard(f.before[0].copy(revision = 1))
    for (mutations in listOf(listOf(stale, f.write("bad")), listOf(f.write("bad"), stale))) {
        assertFalse(database.replicas.mutate(f.scope, mutations, f.wake.copy(generation = "bad")))
        f.unchanged()
    }
    assertTrue(
        database.replicas.commit(
            f.before[0].handle,
            0,
            "new-source",
            listOf(f.document.copy(version = 2)),
            true,
        )
    )
    assertFalse(database.replicas.mutate(f.scope, listOf(f.write("old-source"), f.guard())))
    assertEquals(f.before[1], database.replicas.checkpoint(f.scope, f.target))
}

suspend fun verifyReplicaCheckExactStaging(database: AppDatabase) {
    val f = ReplicaCheckFixture(database)
    f.seed()
    for (wrong in
        listOf(
            f.before[0].copy(payload = "different"),
            f.before[0].copy(published = false),
            f.before[0].copy(handle = f.before[0].handle.copy(generation = "other")),
        )) {
        assertFalse(database.replicas.mutate(f.scope, listOf(f.guard(wrong), f.write("wrong"))))
        f.unchanged()
    }
    assertTrue(database.replicas.begin(f.scope, f.source, "staging", "staged", "initial"))
    assertFalse(database.replicas.mutate(f.scope, listOf(f.guard(), f.write("wrong"))))
    val staged = assertNotNull(database.replicas.checkpoint(f.scope, f.source))
    assertFalse(staged.published)
    assertTrue(database.replicas.mutate(f.scope, listOf(f.guard(staged), f.write("correct"))))
    assertEquals(staged, database.replicas.checkpoint(f.scope, f.source))
    assertEquals(
        f.before[0],
        database.replicas.observePublishedCheckpoint(f.scope, f.source).first(),
    )
    assertEquals(listOf(f.document), database.replicas.observe(f.scope, f.source).first().documents)
}

suspend fun verifyReplicaCheckOwnership(database: AppDatabase) {
    val f = ReplicaCheckFixture(database)
    f.seed()
    val foreign =
        f.before[0].copy(handle = f.before[0].handle.copy(scope = ReplicaScope("foreign", "other")))
    assertFailsWith<IllegalArgumentException> {
        database.replicas.mutate(f.scope, listOf(f.guard(foreign), f.write("bad")))
    }
    f.unchanged()
    database.accounts.activate("replacement", "other")
    assertFalse(
        database.replicas.mutate(
            f.scope,
            listOf(f.guard(), f.write("bad")),
            f.wake.copy(generation = "bad"),
        )
    )
    database.accounts.activate(f.scope.sessionId, f.scope.accountKey)
    f.unchanged()
}

suspend fun verifyReplicaCheckBounds(database: AppDatabase) {
    val f = ReplicaCheckFixture(database)
    f.seed()
    val invalid =
        listOf(
            listOf(f.guard()),
            listOf(f.guard(), ReplicaMutation.Check(f.target, f.before[1])),
            listOf(ReplicaMutation.Check(f.target, f.before[1]), f.write("bad")),
            listOf(f.guard(), f.write("bad"), ReplicaMutation.Check("third", null)),
            listOf(ReplicaMutation.Check("", null), f.write("bad")),
        )
    for (batch in invalid) {
        assertFailsWith<IllegalArgumentException> {
            database.replicas.mutate(f.scope, batch, f.wake.copy(generation = "bad"))
        }
        f.unchanged()
    }
}

suspend fun verifyReplicaCheckWakeConflict(database: AppDatabase) {
    val f = ReplicaCheckFixture(database)
    f.seed()
    for (wake in listOf(f.wake, WorkWakeRequest("new-key", "new", 1))) {
        assertFalse(database.replicas.mutate(f.scope, listOf(f.guard(), f.write("bad")), wake))
        f.unchanged()
    }
    assertNull(database.workWakes.get("new-key"))
}

suspend fun verifyReplicaCheckRollback(database: AppDatabase) {
    val f = ReplicaCheckFixture(database)
    f.seed()
    val colliding =
        listOf(ReplicaDocument("a", 1, "same", "first"), ReplicaDocument("b", 1, "same", "second"))
    val writes =
        listOf<ReplicaMutation>(
            f.write("broken").copy(documents = colliding),
            ReplicaMutation.Replace(
                f.target,
                f.before[1],
                ReplicaSnapshot("broken", "broken", colliding),
            ),
        )
    for (write in writes) {
        assertFails {
            database.replicas.mutate(
                f.scope,
                listOf(f.guard(), write),
                f.wake.copy(generation = "broken"),
            )
        }
        f.unchanged()
        assertNull(database.replicas.document(f.scope, f.target, "a"))
    }
}

suspend fun verifyReplicaCheckConcurrentWriters(database: AppDatabase) = coroutineScope {
    val f = ReplicaCheckFixture(database)
    f.seed()
    val results =
        listOf("first", "second")
            .map { label ->
                async {
                    label to
                        database.replicas.mutate(
                            f.scope,
                            listOf(f.guard(), f.write(label)),
                            f.wake.copy(generation = label),
                        )
                }
            }
            .awaitAll()
    val label = results.single { it.second }.first
    assertEquals(1, results.count { !it.second })
    f.sourceUnchanged()
    assertEquals(label, database.replicas.document(f.scope, f.target, "item")?.payload)
    assertEquals(WorkWakeRecord(f.wake.key, label, true), database.workWakes.get(f.wake.key))
}

suspend fun verifyReplicaCheckCancellation(database: AppDatabase) = coroutineScope {
    val f = ReplicaCheckFixture(database)
    f.seed()
    launch {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> {
                database.replicas.mutate(f.scope, listOf(f.guard(), f.write("never")))
            }
        }
        .join()
    f.unchanged()
    repeat(4) { round ->
        val before = assertNotNull(database.replicas.checkpoint(f.scope, f.target))
        val wakeBefore = database.workWakes.get(f.wake.key)
        val label = "cancel-$round"
        val writer =
            launch(start = CoroutineStart.UNDISPATCHED) {
                database.replicas.mutate(
                    f.scope,
                    listOf(f.guard(), f.write(label, before)),
                    f.wake.copy(generation = label),
                )
            }
        writer.cancelAndJoin()
        f.sourceUnchanged()
        val after = database.replicas.checkpoint(f.scope, f.target)
        if (after == before) assertEquals(wakeBefore, database.workWakes.get(f.wake.key))
        else {
            assertEquals(label, after?.payload)
            assertEquals(label, database.replicas.document(f.scope, f.target, "item")?.payload)
            assertEquals(
                WorkWakeRecord(f.wake.key, label, true),
                database.workWakes.get(f.wake.key),
            )
        }
    }
}

suspend fun verifyReplicaCheckLostResponse(database: AppDatabase) {
    val f = ReplicaCheckFixture(database)
    f.seed()
    val mutations = listOf(f.guard(), f.write("retained"))
    val wake = f.wake.copy(generation = "retained")
    assertFailsWith<IllegalStateException> {
        check(database.replicas.mutate(f.scope, mutations, wake))
        error("Response lost after commit")
    }
    f.sourceUnchanged()
    assertTrue(database.workWakes.acknowledge(wake.key, wake.generation))
    assertFalse(database.replicas.mutate(f.scope, mutations, wake))
    assertEquals("retained", database.replicas.document(f.scope, f.target, "item")?.payload)
    assertEquals(WorkWakeRecord(wake.key, wake.generation, false), database.workWakes.get(wake.key))
}

suspend fun verifyReplicaCheckArchiveReplay(database: AppDatabase) {
    val f = ReplicaCheckFixture(database)
    f.seed()
    val scope = CommandScope(f.scope.sessionId, requireNotNull(f.scope.accountKey))
    val record = CommandRecord("checked/commands", "command", "resource", "01", "request", "state")
    assertEquals(CommandInsertResult.Stored, database.commands.insert(scope, record))
    val mutations = listOf(f.guard(), f.write("archived"))
    assertTrue(database.commands.archiveAndMutate(scope, record, "evidence", mutations))
    f.sourceUnchanged()
    assertNull(database.commands.get(scope, record.partition, record.id))
    assertTrue(database.replicas.commit(f.before[0].handle, 0, "advanced", emptyList(), true))
    val target = database.replicas.checkpoint(f.scope, f.target)
    assertTrue(database.commands.archiveAndMutate(scope, record, "evidence", mutations))
    assertFalse(database.commands.archiveAndMutate(scope, record, "different-evidence", mutations))
    assertEquals(target, database.replicas.checkpoint(f.scope, f.target))
    assertEquals("advanced", database.replicas.checkpoint(f.scope, f.source)?.payload)
}

suspend fun prepareReplicaCheckRestart(database: AppDatabase) {
    val f = ReplicaCheckFixture(database)
    f.seed()
    assertTrue(
        database.replicas.mutate(
            f.scope,
            listOf(f.guard(), f.write("retained")),
            f.wake.copy(generation = "retained"),
        )
    )
}

suspend fun verifyReplicaCheckRestart(database: AppDatabase) {
    val f = ReplicaCheckFixture(database)
    val renewed = f.scope.copy(sessionId = "reopened")
    database.accounts.activate(renewed.sessionId, renewed.accountKey)
    val source = assertNotNull(database.replicas.checkpoint(renewed, f.source))
    assertEquals(0L, source.revision)
    assertEquals("initial", source.payload)
    assertEquals(listOf(f.document), database.replicas.observe(renewed, f.source).first().documents)
    assertEquals("retained", database.replicas.document(renewed, f.target, "item")?.payload)
    assertEquals(WorkWakeRecord(f.wake.key, "retained", true), database.workWakes.get(f.wake.key))
}
