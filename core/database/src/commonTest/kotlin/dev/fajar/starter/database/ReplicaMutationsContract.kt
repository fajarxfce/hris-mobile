package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private class ReplicaPairFixture(val database: AppDatabase) {
    val scope = ReplicaScope("pair-session", "pair-account")
    val partitions = listOf("source/records", "projection/records")
    val wake = WorkWakeRequest("pair.recovery", "initial", 2)
    val records = listOf(ReplicaDocument("item", 1, "01", "sealed", setOf("visible")))
    lateinit var initial: List<ReplicaCheckpoint>

    suspend fun seed() {
        database.accounts.activate(scope.sessionId, scope.accountKey)
        assertTrue(
            database.replicas.mutate(
                scope,
                partitions.map { partition ->
                    ReplicaMutation.Replace(
                        partition,
                        null,
                        ReplicaSnapshot("initial", "initial", records),
                    )
                },
                wake,
            )
        )
        initial = checkpoints()
    }

    suspend fun checkpoints(): List<ReplicaCheckpoint> =
        partitions.map { assertNotNull(database.replicas.checkpoint(scope, it)) }

    fun changes(
        label: String,
        checkpoints: List<ReplicaCheckpoint> = initial,
    ): List<ReplicaMutation> =
        checkpoints.map { before ->
            ReplicaMutation.Commit(
                before.handle,
                before.revision,
                label,
                listOf(records.single().copy(version = before.revision + 2, payload = label)),
                true,
            )
        }

    suspend fun unchanged() {
        assertEquals(initial, checkpoints())
        for (partition in partitions) assertEquals(
            records,
            database.replicas.observe(scope, partition).first().documents,
        )
        assertEquals(
            WorkWakeRecord(wake.key, wake.generation, true),
            database.workWakes.get(wake.key),
        )
    }
}

suspend fun verifyReplicaPairReplacementAndRemoval(database: AppDatabase) {
    val f = ReplicaPairFixture(database)
    f.seed()
    val replacements =
        f.partitions.mapIndexed { index, partition ->
            ReplicaMutation.Replace(
                partition,
                f.initial[index],
                ReplicaSnapshot("replaced", "replaced", f.records),
            )
        }
    assertTrue(database.replicas.mutate(f.scope, replacements))
    assertEquals(listOf("replaced", "replaced"), f.checkpoints().map { it.payload })
    assertTrue(
        database.replicas.mutate(
            f.scope,
            f.checkpoints().map { ReplicaMutation.Replace(it.handle.partition, it, null) },
        )
    )
    for (partition in f.partitions) {
        assertNull(database.replicas.checkpoint(f.scope, partition))
        assertTrue(database.replicas.observe(f.scope, partition).first().documents.isEmpty())
    }
}

suspend fun verifyReplicaPairMixedStaging(database: AppDatabase) {
    val f = ReplicaPairFixture(database)
    f.seed()
    assertTrue(database.replicas.begin(f.scope, f.partitions[0], "staging", "start", "initial"))
    val staged = f.checkpoints()[0]
    assertTrue(
        database.replicas.mutate(
            f.scope,
            listOf(
                ReplicaMutation.Commit(
                    staged.handle,
                    staged.revision,
                    "staged",
                    f.records.map { it.copy(payload = "new") },
                    false,
                ),
                ReplicaMutation.Replace(
                    f.partitions[1],
                    f.initial[1],
                    ReplicaSnapshot("new", "new", f.records),
                ),
            ),
        )
    )
    assertEquals(f.records, database.replicas.observe(f.scope, f.partitions[0]).first().documents)
    assertFalse(f.checkpoints()[0].published)
    val before = f.checkpoints()
    assertTrue(database.replicas.mutate(f.scope, f.changes("published", before)))
    assertTrue(f.checkpoints().all { it.published })
    assertEquals(listOf("published", "published"), f.checkpoints().map { it.payload })
}

suspend fun verifyReplicaPairStaleConditions(database: AppDatabase) {
    val f = ReplicaPairFixture(database)
    f.seed()
    val original = f.changes("next")
    for (bad in
        listOf(
            (original[1] as ReplicaMutation.Commit).copy(expectedRevision = 1),
            (original[1] as ReplicaMutation.Commit).copy(
                handle = f.initial[1].handle.copy(generation = "absent")
            ),
            ReplicaMutation.Replace(f.partitions[1], f.initial[1].copy(payload = "wrong"), null),
            ReplicaMutation.Replace(f.partitions[1], f.initial[1].copy(published = false), null),
            ReplicaMutation.Replace(f.partitions[1], null, null),
        )) {
        for (entries in listOf(listOf(original[0], bad), listOf(bad, original[0]))) {
            assertFalse(
                database.replicas.mutate(f.scope, entries, f.wake.copy(generation = "next"))
            )
            f.unchanged()
        }
    }
}

suspend fun verifyReplicaPairGenerationReuse(database: AppDatabase) {
    val f = ReplicaPairFixture(database)
    f.seed()
    val reused =
        ReplicaMutation.Replace(
            f.partitions[1],
            f.initial[1],
            ReplicaSnapshot("initial", "reused", f.records),
        )
    for (entries in
        listOf(listOf(f.changes("next")[0], reused), listOf(reused, f.changes("next")[0]))) {
        assertFalse(database.replicas.mutate(f.scope, entries, f.wake.copy(generation = "next")))
        f.unchanged()
    }
}

suspend fun verifyReplicaPairConstraintRollback(database: AppDatabase) {
    val f = ReplicaPairFixture(database)
    f.seed()
    val colliding =
        listOf(ReplicaDocument("a", 1, "same", "first"), ReplicaDocument("b", 1, "same", "second"))
    val failures =
        listOf<ReplicaMutation>(
            ReplicaMutation.Replace(
                f.partitions[1],
                f.initial[1],
                ReplicaSnapshot("broken", "broken", colliding),
            ),
            (f.changes("broken")[1] as ReplicaMutation.Commit).copy(documents = colliding),
        )
    for (broken in failures) {
        assertFails {
            database.replicas.mutate(
                f.scope,
                listOf(ReplicaMutation.Replace(f.partitions[0], f.initial[0], null), broken),
                f.wake.copy(generation = "broken"),
            )
        }
        f.unchanged()
        for (partition in f.partitions) assertNull(
            database.replicas.document(f.scope, partition, "a")
        )
    }
}

suspend fun verifyReplicaPairVersionsAndTags(database: AppDatabase) {
    val f = ReplicaPairFixture(database)
    f.seed()
    val tombstone =
        (f.changes("delete")[0] as ReplicaMutation.Commit).copy(
            documents =
                listOf(f.records.single().copy(version = 5, payload = null, tags = emptySet()))
        )
    assertTrue(database.replicas.mutate(f.scope, listOf(tombstone, f.changes("delete")[1])))
    val before = f.checkpoints()
    val resurrect =
        (f.changes("older", before)[0] as ReplicaMutation.Commit).copy(
            documents = listOf(f.records.single().copy(version = 5))
        )
    assertTrue(database.replicas.mutate(f.scope, listOf(resurrect, f.changes("older", before)[1])))
    assertNull(database.replicas.document(f.scope, f.partitions[0], "item"))
    assertTrue(
        database.replicas
            .observeQueries(f.scope, f.partitions[0], listOf(ReplicaQuery(tag = "visible")))
            .first()
            .pages
            .single()
            .isEmpty()
    )
    assertEquals("older", database.replicas.document(f.scope, f.partitions[1], "item")?.payload)
}

suspend fun verifyReplicaPairOwnership(database: AppDatabase) {
    val f = ReplicaPairFixture(database)
    f.seed()
    for ((session, account) in listOf(null to null, "replacement" to "other-account")) {
        database.accounts.activate(session, account)
        assertFalse(
            database.replicas.mutate(
                f.scope,
                f.changes("foreign"),
                f.wake.copy(generation = "foreign"),
            )
        )
        database.accounts.activate(f.scope.sessionId, f.scope.accountKey)
        f.unchanged()
    }
    val renewed = f.scope.copy(sessionId = "renewed")
    database.accounts.activate(renewed.sessionId, renewed.accountKey)
    assertFalse(database.replicas.mutate(f.scope, f.changes("stale")))
    val originals = f.partitions.map { assertNotNull(database.replicas.checkpoint(renewed, it)) }
    assertTrue(database.replicas.mutate(renewed, f.changes("renewed", originals)))
    for (partition in f.partitions) assertEquals(
        "renewed",
        database.replicas.document(renewed, partition, "item")?.payload,
    )
}

suspend fun verifyReplicaPairBounds(database: AppDatabase) {
    val f = ReplicaPairFixture(database)
    f.seed()
    val valid = f.changes("next")
    val commit = valid[0] as ReplicaMutation.Commit
    val invalid =
        listOf(
            emptyList(),
            valid + commit,
            listOf(commit, commit),
            listOf(
                commit.copy(
                    handle = commit.handle.copy(scope = f.scope.copy(accountKey = "foreign"))
                ),
                valid[1],
            ),
            listOf(commit.copy(expectedRevision = 9_007_199_254_740_991L), valid[1]),
            listOf(
                commit.copy(
                    documents = (0..200).map { ReplicaDocument("item-$it", 1, "$it", "sealed") }
                ),
                valid[1],
            ),
            listOf(
                commit.copy(
                    documents = listOf(f.records.single().copy(payload = "x".repeat(131073)))
                ),
                valid[1],
            ),
        )
    for (entries in invalid) {
        assertFailsWith<IllegalArgumentException> { database.replicas.mutate(f.scope, entries) }
        f.unchanged()
    }
    assertFailsWith<IllegalArgumentException> {
        database.replicas.mutate(f.scope, valid, f.wake.copy(maxKeys = 0))
    }
    f.unchanged()
}

suspend fun verifyReplicaPairWakeConflict(database: AppDatabase) {
    val f = ReplicaPairFixture(database)
    f.seed()
    assertFalse(database.replicas.mutate(f.scope, f.changes("reuse"), f.wake))
    f.unchanged()
    assertFalse(
        database.replicas.mutate(
            f.scope,
            f.changes("capacity"),
            WorkWakeRequest("different.recovery", "next", 1),
        )
    )
    f.unchanged()
    assertTrue(database.workWakes.acknowledge(f.wake.key, f.wake.generation))
    assertFalse(database.replicas.mutate(f.scope, f.changes("resurrect"), f.wake))
    assertEquals(f.initial, f.checkpoints())
    assertEquals(
        WorkWakeRecord(f.wake.key, f.wake.generation, false),
        database.workWakes.get(f.wake.key),
    )
}

suspend fun verifyReplicaPairConcurrentWriters(database: AppDatabase) = coroutineScope {
    val f = ReplicaPairFixture(database)
    f.seed()
    repeat(6) { round ->
        val before = f.checkpoints()
        val start = CompletableDeferred<Unit>()
        val writers =
            (0..1).map { writer ->
                async {
                    start.await()
                    val label = "round-$round-writer-$writer"
                    val entries =
                        f.changes(label, before).let { if (writer == 0) it else it.reversed() }
                    label to
                        database.replicas.mutate(f.scope, entries, f.wake.copy(generation = label))
                }
            }
        start.complete(Unit)
        val outcomes = writers.map { it.await() }
        val winner = outcomes.single { it.second }.first
        assertEquals(1, outcomes.count { !it.second })
        assertTrue(f.checkpoints().all { it.payload == winner })
        for (partition in f.partitions) assertEquals(
            winner,
            database.replicas.document(f.scope, partition, "item")?.payload,
        )
        assertEquals(WorkWakeRecord(f.wake.key, winner, true), database.workWakes.get(f.wake.key))
    }
}

suspend fun verifyReplicaPairFrozenInput(database: AppDatabase) = coroutineScope {
    val f = ReplicaPairFixture(database)
    f.seed()
    val tags = mutableSetOf("captured")
    val documents =
        mutableListOf(f.records.single().copy(version = 2, payload = "captured", tags = tags))
    val mutations =
        f.changes("captured")
            .map { (it as ReplicaMutation.Commit).copy(documents = documents) }
            .toMutableList()
    val writer =
        async(start = CoroutineStart.UNDISPATCHED) { database.replicas.mutate(f.scope, mutations) }
    tags.clear()
    documents.clear()
    mutations.clear()
    assertTrue(writer.await())
    for (partition in f.partitions) {
        val record = assertNotNull(database.replicas.document(f.scope, partition, "item"))
        assertEquals("captured", record.payload)
        assertEquals(setOf("captured"), record.tags)
    }
}

suspend fun verifyReplicaPairCancellation(database: AppDatabase) = coroutineScope {
    val f = ReplicaPairFixture(database)
    f.seed()
    launch {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> {
                database.replicas.mutate(f.scope, f.changes("never"))
            }
        }
        .join()
    f.unchanged()
    repeat(6) { round ->
        val before = f.checkpoints()
        val oldWake = database.workWakes.get(f.wake.key)
        val label = "cancel-$round"
        val writer =
            launch(start = CoroutineStart.UNDISPATCHED) {
                database.replicas.mutate(
                    f.scope,
                    f.changes(label, before),
                    f.wake.copy(generation = label),
                )
            }
        writer.cancelAndJoin()
        val after = f.checkpoints()
        if (after == before) assertEquals(oldWake, database.workWakes.get(f.wake.key))
        else {
            assertTrue(after.all { it.payload == label })
            assertEquals(
                WorkWakeRecord(f.wake.key, label, true),
                database.workWakes.get(f.wake.key),
            )
        }
        val payloads = f.partitions.map { database.replicas.document(f.scope, it, "item")?.payload }
        assertEquals(payloads[0], payloads[1])
    }
}

suspend fun verifyReplicaPairLostResponse(database: AppDatabase) {
    val f = ReplicaPairFixture(database)
    f.seed()
    val request = f.changes("committed")
    val wake = f.wake.copy(generation = "committed")
    assertFailsWith<IllegalStateException> {
        check(database.replicas.mutate(f.scope, request, wake))
        error("Response lost after the atomic write")
    }
    assertTrue(f.checkpoints().all { it.payload == "committed" })
    assertTrue(database.workWakes.acknowledge(wake.key, wake.generation))
    assertFalse(database.replicas.mutate(f.scope, request, wake))
    assertTrue(f.checkpoints().all { it.payload == "committed" })
    assertEquals(WorkWakeRecord(wake.key, wake.generation, false), database.workWakes.get(wake.key))
}

suspend fun prepareReplicaPairRestart(database: AppDatabase) {
    val f = ReplicaPairFixture(database)
    f.seed()
    assertTrue(
        database.replicas.mutate(
            f.scope,
            f.changes("retained"),
            f.wake.copy(generation = "retained"),
        )
    )
}

suspend fun verifyReplicaPairRestart(database: AppDatabase) {
    val f = ReplicaPairFixture(database)
    val renewed = f.scope.copy(sessionId = "reopened")
    database.accounts.activate(renewed.sessionId, renewed.accountKey)
    for (partition in f.partitions) {
        assertEquals("retained", database.replicas.checkpoint(renewed, partition)?.payload)
        assertEquals("retained", database.replicas.document(renewed, partition, "item")?.payload)
    }
    assertEquals(WorkWakeRecord(f.wake.key, "retained", true), database.workWakes.get(f.wake.key))
}
