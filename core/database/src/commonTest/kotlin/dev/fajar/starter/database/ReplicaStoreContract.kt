package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private const val session = "replica-session"
private const val partition = "company-a/inbox"
private val first = ReplicaHandle(session, partition, "generation-1")

suspend fun verifyReplicaPublication(database: AppDatabase) {
    val store = database.replicas
    database.accounts.activate(session)
    assertTrue(store.begin(session, partition, first.generation, "sealed-bootstrap-1", null))
    assertFalse(store.checkpoint(session, partition)!!.published)
    assertTrue(store.observe(session, partition).first().documents.isEmpty())
    val a = ReplicaDocument("a", 3, "01-a", "sealed-a3")
    assertTrue(store.commit(first, 0, "sealed-bootstrap-2", listOf(a), false))
    assertNull(store.observe(session, partition).first().checkpoint)
    assertNull(store.observeDocument(session, partition, "a").first().checkpoint)
    assertTrue(store.commit(first, 1, "sealed-changes-1", emptyList(), true))
    val initial = store.observe(session, partition).first()
    assertEquals(listOf(a), initial.documents)
    assertEquals("sealed-changes-1", initial.checkpoint!!.payload)
    assertEquals(2, initial.checkpoint.revision)
    assertTrue(initial.checkpoint.published)
    assertEquals(initial, store.observeDocument(session, partition, "a").first())
    assertEquals(
        initial.checkpoint,
        store.observeDocument(session, partition, "absent").first().checkpoint,
    )
    assertTrue(store.observeDocument(session, partition, "absent").first().documents.isEmpty())

    val second = first.copy(generation = "generation-2")
    assertFalse(store.begin(session, partition, second.generation, "new-bootstrap", "obsolete"))
    assertTrue(
        store.begin(session, partition, second.generation, "new-bootstrap", first.generation)
    )
    assertFalse(store.commit(first, 2, "obsolete-commit", emptyList(), true))
    assertEquals(initial, store.observe(session, partition).first())
    assertEquals(second, store.checkpoint(session, partition)!!.handle)
    assertEquals(initial, store.observeDocument(session, partition, "a").first())
    val b = ReplicaDocument("b", 1, "02-b", "sealed-b1")
    assertTrue(store.commit(second, 0, "next-bootstrap", listOf(b), false))
    assertEquals(initial, store.observe(session, partition).first())
    assertTrue(store.commit(second, 1, "caught-up", emptyList(), true))
    assertEquals(listOf(b), store.observe(session, partition).first().documents)
    assertNull(store.document(session, partition, "a"))
    val removed = store.observeDocument(session, partition, "a").first()
    assertEquals(second, removed.checkpoint!!.handle)
    assertTrue(removed.documents.isEmpty())
    assertNull(store.observeDocument("another-session", partition, "b").first().checkpoint)
    assertNull(store.observeDocument(session, "company-b/inbox", "b").first().checkpoint)

    val abandoned = second.copy(generation = "abandoned")
    assertTrue(store.begin(session, partition, abandoned.generation, "start", second.generation))
    assertTrue(store.commit(abandoned, 0, "partial", listOf(a), false))
    val replacement = second.copy(generation = "replacement")
    assertTrue(
        store.begin(session, partition, replacement.generation, "restart", abandoned.generation)
    )
    assertFalse(store.commit(abandoned, 1, "late", emptyList(), true))
    assertTrue(store.commit(replacement, 0, "empty", emptyList(), true))
    assertTrue(store.observe(session, partition).first().documents.isEmpty())
    assertEquals("empty", store.observe(session, partition).first().checkpoint!!.payload)
}

suspend fun verifyReplicaAtomicity(database: AppDatabase) {
    val store = database.replicas
    database.accounts.activate("atomic-session")
    val handle = ReplicaHandle("atomic-session", partition, "atomic-generation")
    assertTrue(store.begin(handle.sessionId, partition, handle.generation, "initial", null))
    val initial = ReplicaDocument("a", 1, "01-a", "sealed-original")
    assertTrue(store.commit(handle, 0, "original", listOf(initial), true))
    assertFails {
        store.commit(
            handle,
            1,
            "must-rollback",
            listOf(
                ReplicaDocument("b", 1, "02-b", "sealed-valid"),
                ReplicaDocument("c", 1, "01-a", "sealed-constraint-conflict"),
            ),
            true,
        )
    }
    assertEquals("original", store.checkpoint(handle.sessionId, partition)!!.payload)
    assertEquals(listOf(initial), store.observe(handle.sessionId, partition).first().documents)
    coroutineScope {
        val requests =
            (1..2).map { writer ->
                async {
                    store.commit(
                        handle,
                        1,
                        "writer-$writer",
                        listOf(ReplicaDocument("a", 2, "01-a", "sealed-writer-$writer")),
                        true,
                    )
                }
            }
        assertEquals(1, requests.count { it.await() })
    }
    val committed = store.observe(handle.sessionId, partition).first()
    assertEquals(2, committed.checkpoint!!.revision)
    assertEquals("sealed-" + committed.checkpoint.payload, committed.documents.single().payload)
    assertFalse(store.commit(handle, 1, "old-checkpoint", listOf(initial), true))
}

suspend fun verifyReplicaTombstones(database: AppDatabase) {
    val store = database.replicas
    database.accounts.activate("tombstone-session")
    val handle = ReplicaHandle("tombstone-session", partition, "tombstone-generation")
    assertTrue(store.begin(handle.sessionId, partition, handle.generation, "initial", null))
    val newer = ReplicaDocument("a", 5, "01-a", "sealed-newer-than-feed")
    assertTrue(store.commit(handle, 0, "cursor-1", listOf(newer), true))
    assertTrue(
        store.commit(handle, 1, "cursor-2", listOf(newer.copy(version = 4, payload = "old")), true)
    )
    assertEquals(newer, store.document(handle.sessionId, partition, "a"))
    assertTrue(store.commit(handle, 2, "cursor-3", listOf(newer.copy(payload = null)), true))
    assertNull(store.document(handle.sessionId, partition, "a"))
    assertTrue(store.commit(handle, 3, "cursor-4", listOf(newer), true))
    assertTrue(store.observe(handle.sessionId, partition).first().documents.isEmpty())
    assertTrue(store.commit(handle, 4, "cursor-5", listOf(newer.copy(version = 6)), true))
    assertEquals(6, store.document(handle.sessionId, partition, "a")!!.version)
}

suspend fun verifyReplicaIsolation(database: AppDatabase) {
    val store = database.replicas
    database.accounts.activate("scope-session")
    val a = ReplicaHandle("scope-session", "company-a/inbox", "shared-generation")
    val b = a.copy(partition = "company-b/inbox")
    for (handle in listOf(a, b)) {
        assertTrue(
            store.begin(handle.sessionId, handle.partition, handle.generation, "start", null)
        )
        assertTrue(
            store.commit(
                handle,
                0,
                "ready",
                listOf(ReplicaDocument("same-id", 0, "01-id", "sealed-" + handle.partition)),
                true,
            )
        )
    }
    assertNotEquals(
        store.document(a.sessionId, a.partition, "same-id"),
        store.document(b.sessionId, b.partition, "same-id"),
    )
    assertTrue(store.clear(a.sessionId, a.partition))
    assertNull(store.checkpoint(a.sessionId, a.partition))
    assertFalse(store.commit(a, 1, "late", emptyList(), true))
    assertNotNull(store.document(b.sessionId, b.partition, "same-id"))
    database.accounts.activate("replacement-session")
    assertTrue(store.observe(a.sessionId, b.partition).first().documents.isEmpty())
    assertNull(store.checkpoint("replacement-session", b.partition))
    assertFalse(store.clear(a.sessionId, b.partition))
    assertFalse(store.begin(a.sessionId, a.partition, "late-start", "late", null))
    assertFalse(store.commit(b, 1, "late-write", emptyList(), true))
    assertTrue(store.begin("replacement-session", b.partition, "new-owner", "new", null))
    assertNull(store.document(a.sessionId, b.partition, "same-id"))
}

suspend fun verifyReplicaPaginationAndBounds(database: AppDatabase) {
    val store = database.replicas
    database.accounts.activate("paged-session")
    val handle = ReplicaHandle("paged-session", partition, "paged-generation")
    assertTrue(store.begin(handle.sessionId, partition, handle.generation, "start", null))
    val rows =
        (1..120).map {
            val id = it.toString().padStart(3, '0')
            ReplicaDocument(id, 0, id, if (it % 3 == 0) null else "sealed-$id")
        }
    assertTrue(store.commit(handle, 0, "ready", rows, true))
    val firstPage = store.observe(handle.sessionId, partition, limit = 50).first()
    assertEquals(50, firstPage.documents.size)
    val nextPage =
        store.observe(handle.sessionId, partition, firstPage.documents.last().sortKey, 50).first()
    assertEquals(rows.filter { it.payload != null }, firstPage.documents + nextPage.documents)
    assertFailsWith<IllegalArgumentException> {
        store.observe(handle.sessionId, partition, limit = 201).first()
    }
    assertFailsWith<IllegalArgumentException> {
        store.commit(handle, 1, "duplicate", listOf(rows[0], rows[0]), true)
    }
    assertFailsWith<IllegalArgumentException> {
        store.commit(
            handle,
            1,
            "oversized",
            listOf(rows[0].copy(payload = "x".repeat(131_073))),
            true,
        )
    }
    assertFailsWith<IllegalArgumentException> {
        store.commit(handle, 9_007_199_254_740_991L, "unsafe-integer", emptyList(), true)
    }
    assertEquals("ready", store.checkpoint(handle.sessionId, partition)!!.payload)
}

suspend fun verifyReplicaCancellation(database: AppDatabase) = coroutineScope {
    database.accounts.activate("cancel-session")
    val store = database.replicas
    val handle = ReplicaHandle("cancel-session", partition, "cancel-generation")
    assertTrue(store.begin(handle.sessionId, partition, handle.generation, "before", null))
    val documents =
        (1..200).map {
            val id = it.toString().padStart(3, '0')
            ReplicaDocument(id, 0, id, "sealed-$id")
        }
    val commit =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.commit(handle, 0, "after", documents, true)
        }
    yield()
    commit.cancelAndJoin()
    val checkpoint = store.checkpoint(handle.sessionId, partition)!!
    when (checkpoint.revision) {
        0L -> {
            assertEquals("before", checkpoint.payload)
            assertTrue(store.commit(handle, 0, "empty", emptyList(), true))
            assertTrue(
                store.observe(handle.sessionId, partition, limit = 200).first().documents.isEmpty()
            )
        }
        1L -> {
            assertEquals("after", checkpoint.payload)
            assertEquals(
                documents,
                store.observe(handle.sessionId, partition, limit = 200).first().documents,
            )
        }
        else -> fail("A cancelled transaction must be absent or fully committed.")
    }
}
