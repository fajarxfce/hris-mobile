package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*

suspend fun verifyWorkWakeGenerations(store: WorkWakeStore) {
    assertNull(store.get("chat.recovery"))
    assertFalse(store.acknowledge("chat.recovery", "first"))
    val first = WorkWakeRecord("chat.recovery", "first", true)
    assertEquals(first, store.request(first.key, first.generation, 8))
    assertEquals(first, store.request(first.key, first.generation, 8))
    val latest = WorkWakeRecord(first.key, "latest", true)
    assertEquals(latest, store.request(latest.key, latest.generation, 8))
    assertFalse(store.acknowledge(first.key, first.generation))
    assertEquals(latest, store.get(first.key))
    assertTrue(store.acknowledge(latest.key, latest.generation))
    val done = latest.copy(pending = false)
    assertEquals(done, store.get(first.key))
    assertEquals(done, store.request(latest.key, latest.generation, 8))
    assertFalse(store.acknowledge(latest.key, latest.generation))
    val next = WorkWakeRecord(first.key, "next", true)
    assertEquals(next, store.request(next.key, next.generation, 8))
    assertFalse(store.acknowledge(latest.key, latest.generation))
    assertEquals(next, store.get(first.key))
}

suspend fun verifyWorkWakeCapacity(store: WorkWakeStore) = coroutineScope {
    val ready = CompletableDeferred<Unit>()
    val attempts =
        (0 until 40).map { index ->
            async {
                ready.await()
                store.request("task-$index", "generation-$index", 8)
            }
        }
    ready.complete(Unit)
    val stored = attempts.awaitAll().filterNotNull()
    assertEquals(8, stored.size)
    assertEquals(8, stored.map { it.key }.toSet().size)
    val first = stored.first()
    assertEquals(first.copy(generation = "updated"), store.request(first.key, "updated", 8))
    assertNull(store.request("over-capacity", "new", 8))
    assertTrue(store.acknowledge(first.key, "updated"))
    assertNull(store.request("still-over-capacity", "new", 8))
}

suspend fun verifyWorkWakeAcknowledgmentRace(store: WorkWakeStore) = coroutineScope {
    repeat(8) { round ->
        val key = "racing"
        val old = "old-$round"
        val next = "new-$round"
        assertNotNull(store.request(key, old, 1))
        val ready = CompletableDeferred<Unit>()
        val writer = async {
            ready.await()
            store.request(key, next, 1)
        }
        val completion = async {
            ready.await()
            store.acknowledge(key, old)
        }
        ready.complete(Unit)
        assertNotNull(writer.await())
        completion.await()
        assertEquals(WorkWakeRecord(key, next, true), store.get(key))
        assertFalse(store.acknowledge(key, old))
    }
}

suspend fun verifyWorkWakeGlobalOwnership(database: AppDatabase) {
    val record = WorkWakeRecord("chat.recovery", "original", true)
    assertEquals(record, database.workWakes.request(record.key, record.generation, 1))
    database.accounts.activate("first-session", "first-account")
    assertEquals(record, database.workWakes.get(record.key))
    database.accounts.activate(null)
    assertEquals(record, database.workWakes.get(record.key))
    database.accounts.activate("next-session", "next-account")
    assertEquals(record, database.workWakes.get(record.key))
}

suspend fun verifyWorkWakeCancellationAndBounds(store: WorkWakeStore) = coroutineScope {
    for (key in listOf("", "space key", "account/company", "a".repeat(129))) {
        assertFailsWith<IllegalArgumentException> { store.get(key) }
        assertFailsWith<IllegalArgumentException> { store.request(key, "generation", 1) }
        assertFailsWith<IllegalArgumentException> { store.acknowledge(key, "generation") }
    }
    for (generation in listOf("", "space value", "a".repeat(129))) {
        assertFailsWith<IllegalArgumentException> { store.request("task", generation, 1) }
        assertFailsWith<IllegalArgumentException> { store.acknowledge("task", generation) }
    }
    for (limit in listOf(0, 129)) assertFailsWith<IllegalArgumentException> {
        store.request("task", "generation", limit)
    }
    val cancelled = launch {
        currentCoroutineContext().cancel()
        assertFailsWith<CancellationException> { store.request("cancelled", "generation", 1) }
    }
    cancelled.join()
    assertNull(store.get("cancelled"))
    assertNull(store.get("task"))
    val retained = WorkWakeRecord("existing", "original", true)
    assertEquals(retained, store.request(retained.key, retained.generation, 1))
    val cancelledCompletion = launch {
        currentCoroutineContext().cancel()
        assertFailsWith<CancellationException> {
            store.acknowledge(retained.key, retained.generation)
        }
    }
    cancelledCompletion.join()
    assertEquals(retained, store.get(retained.key))
}
