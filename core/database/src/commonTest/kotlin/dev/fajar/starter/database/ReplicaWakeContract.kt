package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private val wakeScope = ReplicaScope("replica-wake-session", "replica-wake-account")
private const val wakePartition = "replica-wake/records"
private val initialWakeSnapshot =
    ReplicaSnapshot("original", "original", listOf(ReplicaDocument("first", 0, "01", "sealed")))
private val initialMutationWake = ReplicaWakeRequest("replica.recovery", "original", 2)

private suspend fun seedReplicaWake(database: AppDatabase): ReplicaCheckpoint {
    database.accounts.activate(wakeScope.sessionId, wakeScope.accountKey)
    assertTrue(
        database.replicas.replace(
            wakeScope,
            wakePartition,
            null,
            initialWakeSnapshot,
            initialMutationWake,
        )
    )
    return assertNotNull(database.replicas.checkpoint(wakeScope, wakePartition))
}

private suspend fun assertInitialReplicaWake(database: AppDatabase, before: ReplicaCheckpoint) {
    assertEquals(before, database.replicas.checkpoint(wakeScope, wakePartition))
    assertEquals(
        initialWakeSnapshot.documents,
        database.replicas.observe(wakeScope, wakePartition).first().documents,
    )
    assertEquals(
        WorkWakeRecord(initialMutationWake.key, initialMutationWake.generation, true),
        database.workWakes.get(initialMutationWake.key),
    )
}

suspend fun verifyReplicaWakeReplacement(database: AppDatabase) {
    val before = seedReplicaWake(database)
    assertInitialReplicaWake(database, before)
    val next = initialMutationWake.copy(generation = "replacement")
    assertTrue(
        database.replicas.replace(
            wakeScope,
            wakePartition,
            before,
            initialWakeSnapshot.copy(generation = "replacement"),
            next,
        )
    )
    assertEquals(WorkWakeRecord(next.key, next.generation, true), database.workWakes.get(next.key))
    assertFalse(database.workWakes.acknowledge(next.key, initialMutationWake.generation))
    val replaced = assertNotNull(database.replicas.checkpoint(wakeScope, wakePartition))
    assertTrue(
        database.replicas.replace(
            wakeScope,
            wakePartition,
            replaced,
            null,
            next.copy(generation = "removed"),
        )
    )
    assertNull(database.replicas.checkpoint(wakeScope, wakePartition))
    assertTrue(database.replicas.observe(wakeScope, wakePartition).first().documents.isEmpty())
    assertEquals(WorkWakeRecord(next.key, "removed", true), database.workWakes.get(next.key))
}

suspend fun verifyReplicaWakeStagedCommit(database: AppDatabase) {
    seedReplicaWake(database)
    val store = database.replicas
    assertTrue(store.begin(wakeScope, wakePartition, "staging", "start", "original"))
    val stage = assertNotNull(store.checkpoint(wakeScope, wakePartition))
    val first = ReplicaDocument("next", 1, "02", "new-sealed", setOf("tag"))
    assertTrue(
        store.commit(
            stage.handle,
            stage.revision,
            "page",
            listOf(first),
            false,
            initialMutationWake.copy(generation = "page"),
        )
    )
    assertEquals(
        initialWakeSnapshot.documents,
        store.observe(wakeScope, wakePartition).first().documents,
    )
    assertEquals(
        WorkWakeRecord(initialMutationWake.key, "page", true),
        database.workWakes.get(initialMutationWake.key),
    )
    val next = assertNotNull(store.checkpoint(wakeScope, wakePartition))
    assertTrue(
        store.commit(
            next.handle,
            next.revision,
            "published",
            emptyList(),
            true,
            initialMutationWake.copy(generation = "published"),
        )
    )
    assertEquals(listOf(first), store.observe(wakeScope, wakePartition).first().documents)
    assertEquals(
        WorkWakeRecord(initialMutationWake.key, "published", true),
        database.workWakes.get(initialMutationWake.key),
    )
}

suspend fun verifyReplicaWakeGenerationReuse(database: AppDatabase) {
    val before = seedReplicaWake(database)
    for (acknowledged in listOf(false, true)) {
        if (acknowledged)
            assertTrue(
                database.workWakes.acknowledge(
                    initialMutationWake.key,
                    initialMutationWake.generation,
                )
            )
        val retained = database.workWakes.get(initialMutationWake.key)
        assertFalse(
            database.replicas.replace(
                wakeScope,
                wakePartition,
                before,
                initialWakeSnapshot.copy(generation = "different"),
                initialMutationWake,
            )
        )
        assertFalse(
            database.replicas.commit(
                before.handle,
                before.revision,
                "different",
                emptyList(),
                true,
                initialMutationWake,
            )
        )
        assertEquals(before, database.replicas.checkpoint(wakeScope, wakePartition))
        assertEquals(retained, database.workWakes.get(initialMutationWake.key))
    }
    assertTrue(
        database.replicas.commit(
            before.handle,
            before.revision,
            "next",
            emptyList(),
            true,
            initialMutationWake.copy(generation = "fresh"),
        )
    )
    assertEquals(
        WorkWakeRecord(initialMutationWake.key, "fresh", true),
        database.workWakes.get(initialMutationWake.key),
    )
}

suspend fun verifyReplicaWakeCapacity(database: AppDatabase) {
    val before = seedReplicaWake(database)
    val full = ReplicaWakeRequest("another.recovery", "new", 1)
    assertFalse(database.replicas.replace(wakeScope, wakePartition, before, null, full))
    assertFalse(
        database.replicas.commit(before.handle, before.revision, "blocked", emptyList(), true, full)
    )
    assertInitialReplicaWake(database, before)
    assertNull(database.workWakes.get(full.key))
    assertTrue(
        database.replicas.commit(
            before.handle,
            before.revision,
            "allowed",
            emptyList(),
            true,
            initialMutationWake.copy(generation = "next", maxKeys = 1),
        )
    )
    assertEquals(
        WorkWakeRecord(initialMutationWake.key, "next", true),
        database.workWakes.get(initialMutationWake.key),
    )
}

suspend fun verifyReplicaWakeOwnershipAndCheckpoint(database: AppDatabase) {
    val before = seedReplicaWake(database)
    val next = initialMutationWake.copy(generation = "stale")
    assertFalse(
        database.replicas.replace(wakeScope, wakePartition, before.copy(revision = 1), null, next)
    )
    assertFalse(
        database.replicas.replace(wakeScope, wakePartition, before, initialWakeSnapshot, next)
    )
    assertFalse(
        database.replicas.commit(
            before.handle,
            before.revision + 1,
            "stale",
            emptyList(),
            true,
            next,
        )
    )
    assertFalse(
        database.replicas.commit(
            before.handle.copy(generation = "foreign"),
            before.revision,
            "stale",
            emptyList(),
            true,
            next,
        )
    )
    assertInitialReplicaWake(database, before)
    database.accounts.activate("replacement-session", "replacement-account")
    assertFalse(database.replicas.replace(wakeScope, wakePartition, before, null, next))
    assertFalse(
        database.replicas.commit(before.handle, before.revision, "stale", emptyList(), true, next)
    )
    database.accounts.activate(wakeScope.sessionId, wakeScope.accountKey)
    assertInitialReplicaWake(database, before)
}

suspend fun verifyReplicaWakeTransactionRollback(database: AppDatabase) {
    val before = seedReplicaWake(database)
    val next = initialMutationWake.copy(generation = "must-rollback")
    // Unique sort conflict with an existing row occurs AFTER a valid write inside the database.
    val rows =
        listOf(
            ReplicaDocument("valid", 0, "02", "valid", setOf("temporary")),
            ReplicaDocument("collision", 0, "01", "invalid"),
        )
    assertFails {
        database.replicas.commit(before.handle, before.revision, "must-rollback", rows, true, next)
    }
    assertInitialReplicaWake(database, before)
    assertNull(database.replicas.document(wakeScope, wakePartition, "valid"))
    assertTrue(
        database.replicas
            .observeQueries(wakeScope, wakePartition, listOf(ReplicaQuery(tag = "temporary")))
            .first()
            .pages
            .single()
            .isEmpty()
    )
    // Replacement also fails in the database, after removing the original head and retaining wake.
    assertFails {
        database.replicas.replace(
            wakeScope,
            wakePartition,
            before,
            ReplicaSnapshot("broken", "broken", listOf(rows[0], rows[1].copy(sortKey = "02"))),
            next,
        )
    }
    assertInitialReplicaWake(database, before)
}

suspend fun verifyReplicaWakeAcknowledgmentRace(database: AppDatabase) = coroutineScope {
    seedReplicaWake(database)
    repeat(8) { round ->
        val before = assertNotNull(database.replicas.checkpoint(wakeScope, wakePartition))
        val old = assertNotNull(database.workWakes.get(initialMutationWake.key))
        val next = initialMutationWake.copy(generation = "race-$round")
        val go = CompletableDeferred<Unit>()
        val writer = async {
            go.await()
            database.replicas.commit(
                before.handle,
                before.revision,
                "race-$round",
                emptyList(),
                true,
                next,
            )
        }
        val consumer = async {
            go.await()
            database.workWakes.acknowledge(old.key, old.generation)
        }
        go.complete(Unit)
        assertTrue(writer.await())
        consumer.await()
        assertEquals(
            WorkWakeRecord(next.key, next.generation, true),
            database.workWakes.get(next.key),
        )
        assertFalse(database.workWakes.acknowledge(old.key, old.generation))
        assertEquals("race-$round", database.replicas.checkpoint(wakeScope, wakePartition)?.payload)
    }
}

suspend fun verifyReplicaWakeConcurrentCapacity(database: AppDatabase) = coroutineScope {
    database.accounts.activate(wakeScope.sessionId, wakeScope.accountKey)
    val go = CompletableDeferred<Unit>()
    val writers =
        (1..2).map { id ->
            async {
                go.await()
                database.replicas.replace(
                    wakeScope,
                    "race/$id",
                    null,
                    initialWakeSnapshot,
                    ReplicaWakeRequest("race.$id", "first", 1),
                )
            }
        }
    go.complete(Unit)
    val outcomes = writers.map { it.await() }
    assertEquals(1, outcomes.count { it })
    for (id in 1..2) {
        assertEquals(outcomes[id - 1], database.replicas.checkpoint(wakeScope, "race/$id") != null)
        assertEquals(outcomes[id - 1], database.workWakes.get("race.$id") != null)
    }
}

suspend fun verifyReplicaWakeLostResponse(database: AppDatabase) {
    val before = seedReplicaWake(database)
    val next = initialMutationWake.copy(generation = "committed")
    val replacement = initialWakeSnapshot.copy(generation = "committed", checkpoint = "committed")
    assertFailsWith<IllegalStateException> {
        check(database.replicas.replace(wakeScope, wakePartition, before, replacement, next))
        error("The caller lost a committed response")
    }
    val committed = assertNotNull(database.replicas.checkpoint(wakeScope, wakePartition))
    assertEquals("committed", committed.payload)
    assertTrue(database.workWakes.acknowledge(next.key, next.generation))
    assertFalse(database.replicas.replace(wakeScope, wakePartition, before, replacement, next))
    assertEquals(committed, database.replicas.checkpoint(wakeScope, wakePartition))
    assertEquals(WorkWakeRecord(next.key, next.generation, false), database.workWakes.get(next.key))
}

suspend fun verifyReplicaWakeBoundsAndCancellation(database: AppDatabase) = coroutineScope {
    val before = seedReplicaWake(database)
    for (invalid in
        listOf(
            initialMutationWake.copy(key = "identity/account"),
            initialMutationWake.copy(generation = ""),
            initialMutationWake.copy(maxKeys = 0),
            initialMutationWake.copy(maxKeys = 129),
        )) {
        assertFailsWith<IllegalArgumentException> {
            database.replicas.replace(wakeScope, wakePartition, before, null, invalid)
        }
        assertFailsWith<IllegalArgumentException> {
            database.replicas.commit(
                before.handle,
                before.revision,
                "invalid",
                emptyList(),
                true,
                invalid,
            )
        }
    }
    val cancelled = launch {
        currentCoroutineContext().cancel()
        assertFailsWith<CancellationException> {
            database.replicas.replace(
                wakeScope,
                wakePartition,
                before,
                null,
                initialMutationWake.copy(generation = "cancelled"),
            )
        }
        assertFailsWith<CancellationException> {
            database.replicas.commit(
                before.handle,
                before.revision,
                "cancelled",
                emptyList(),
                true,
                initialMutationWake.copy(generation = "cancelled"),
            )
        }
    }
    cancelled.join()
    assertInitialReplicaWake(database, before)
    // Omitting a wake preserves the existing raw API behavior, including an acknowledged marker.
    assertTrue(
        database.workWakes.acknowledge(initialMutationWake.key, initialMutationWake.generation)
    )
    assertTrue(
        database.replicas.commit(before.handle, before.revision, "ordinary", emptyList(), true)
    )
    assertEquals(
        WorkWakeRecord(initialMutationWake.key, initialMutationWake.generation, false),
        database.workWakes.get(initialMutationWake.key),
    )
}

suspend fun prepareReplicaWakeRestart(database: AppDatabase) {
    seedReplicaWake(database)
}

suspend fun verifyReplicaWakeRestart(database: AppDatabase, acknowledged: Boolean) {
    val replacement = wakeScope.copy(sessionId = "reopened-session")
    database.accounts.activate(replacement.sessionId, replacement.accountKey)
    val checkpoint = assertNotNull(database.replicas.checkpoint(replacement, wakePartition))
    assertEquals(initialWakeSnapshot.generation, checkpoint.handle.generation)
    assertEquals(
        initialWakeSnapshot.documents,
        database.replicas.observe(replacement, wakePartition).first().documents,
    )
    val wake =
        WorkWakeRecord(initialMutationWake.key, initialMutationWake.generation, !acknowledged)
    assertEquals(wake, database.workWakes.get(wake.key))
    if (!acknowledged) assertTrue(database.workWakes.acknowledge(wake.key, wake.generation))
}
