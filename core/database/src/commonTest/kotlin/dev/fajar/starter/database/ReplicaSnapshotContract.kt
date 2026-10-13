package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

suspend fun verifyReplicaSnapshotReplacement(database: AppDatabase) = coroutineScope {
    val session = "snapshot-session"
    val partition = "scope/selection"
    val store = database.replicas
    database.accounts.activate(session)
    val initial =
        ReplicaSnapshot(
            "original",
            "original-checkpoint",
            listOf(ReplicaDocument("first", 0, "first", "original-document")),
        )
    assertTrue(store.replace(ReplicaScope(session), partition, null, initial))
    val original = store.checkpoint(ReplicaScope(session), partition)!!
    assertTrue(original.published)
    assertEquals(0, original.revision)
    assertEquals(
        initial.documents,
        store.observe(ReplicaScope(session), partition).first().documents,
    )
    assertFalse(
        store.replace(ReplicaScope(session), partition, null, initial.copy(generation = "late"))
    )
    assertFalse(store.replace(ReplicaScope(session), partition, original.copy(revision = 1), null))
    assertFalse(
        store.replace(ReplicaScope(session), partition, original.copy(payload = "different"), null)
    )
    assertFalse(store.replace(ReplicaScope(session), partition, original, initial))
    assertFails {
        store.replace(
            ReplicaScope(session),
            partition,
            original,
            ReplicaSnapshot(
                "broken",
                "must-rollback",
                listOf(
                    ReplicaDocument("a", 0, "same-sort", "one"),
                    ReplicaDocument("b", 0, "same-sort", "two"),
                ),
            ),
        )
    }
    assertEquals(original, store.checkpoint(ReplicaScope(session), partition))
    assertEquals(
        initial.documents,
        store.observe(ReplicaScope(session), partition).first().documents,
    )
    val attempts =
        (1..2).map { id ->
            async {
                store.replace(
                    ReplicaScope(session),
                    partition,
                    original,
                    ReplicaSnapshot(
                        "writer-$id",
                        "writer-$id",
                        listOf(ReplicaDocument("next", 1, "next", "writer-$id")),
                    ),
                )
            }
        }
    assertEquals(1, attempts.count { it.await() })
    val winner = store.observe(ReplicaScope(session), partition).first()
    assertEquals(winner.checkpoint!!.payload, winner.documents.single().payload)
    assertNull(store.document(ReplicaScope(session), partition, "first"))
    assertFalse(store.replace(ReplicaScope(session), partition, original, null))

    assertTrue(
        store.begin(
            ReplicaScope(session),
            partition,
            "staged",
            "staging",
            winner.checkpoint.handle.generation,
        )
    )
    assertFalse(store.replace(ReplicaScope(session), partition, winner.checkpoint, null))
    val staged = store.checkpoint(ReplicaScope(session), partition)!!
    assertTrue(
        store.replace(
            ReplicaScope(session),
            partition,
            staged,
            ReplicaSnapshot("complete", "ready", emptyList()),
        )
    )
    assertFalse(store.commit(staged.handle, staged.revision, "late", emptyList(), true))
    val complete = store.checkpoint(ReplicaScope(session), partition)!!
    assertTrue(complete.published)
    assertTrue(store.observe(ReplicaScope(session), partition).first().documents.isEmpty())

    assertTrue(store.replace(ReplicaScope(session), "other/selection", null, initial))
    assertTrue(store.replace(ReplicaScope(session), partition, complete, null))
    assertNull(store.checkpoint(ReplicaScope(session), partition))
    assertNotNull(store.checkpoint(ReplicaScope(session), "other/selection"))
    assertFalse(store.replace(ReplicaScope("another-session"), partition, null, initial))
    database.accounts.activate("another-session")
    assertFalse(store.replace(ReplicaScope(session), partition, null, initial))
    assertNull(store.checkpoint(ReplicaScope("another-session"), "other/selection"))
}

suspend fun verifyReplicaSnapshotCancellationAndBounds(database: AppDatabase) = coroutineScope {
    val session = "snapshot-cancel-session"
    val partition = "scope/selection"
    val store = database.replicas
    database.accounts.activate(session)
    val initial = ReplicaSnapshot("before", "before", emptyList())
    assertTrue(store.replace(ReplicaScope(session), partition, null, initial))
    val before = store.checkpoint(ReplicaScope(session), partition)!!
    val documents =
        (1..200).map {
            val id = it.toString().padStart(3, '0')
            ReplicaDocument(id, 0, id, "sealed-$id")
        }
    assertFailsWith<IllegalArgumentException> {
        store.replace(
            ReplicaScope(session),
            partition,
            before,
            ReplicaSnapshot("bad", "bad", documents + documents.first()),
        )
    }
    assertFailsWith<IllegalArgumentException> {
        store.replace(
            ReplicaScope(session),
            partition,
            before.copy(
                handle = before.handle.copy(scope = before.handle.scope.copy(sessionId = "other"))
            ),
            null,
        )
    }
    assertEquals(before, store.checkpoint(ReplicaScope(session), partition))
    val replacement =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.replace(
                ReplicaScope(session),
                partition,
                before,
                ReplicaSnapshot("after", "after", documents),
            )
        }
    yield()
    replacement.cancelAndJoin()
    val result = store.observe(ReplicaScope(session), partition, limit = 200).first()
    when (result.checkpoint?.payload) {
        "before" -> assertTrue(result.documents.isEmpty())
        "after" -> assertEquals(documents, result.documents)
        else -> fail("A cancelled replacement must leave a complete snapshot.")
    }
}
