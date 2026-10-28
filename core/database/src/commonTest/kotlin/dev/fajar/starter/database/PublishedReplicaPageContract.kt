package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

suspend fun verifyPublishedReplicaPages(database: AppDatabase) = coroutineScope {
    val store = database.replicas
    val scope = ReplicaScope("page-session", "page-account")
    val prefix = "company-a/drafts/"
    database.accounts.activate(scope.sessionId, scope.accountKey)
    for (partition in listOf(prefix + "c", prefix + "a", prefix + "b", "company-ab/drafts/a")) {
        assertTrue(
            store.replace(scope, partition, null, ReplicaSnapshot("first", partition, emptyList()))
        )
    }
    assertTrue(store.begin(scope, prefix + "0-staging", "staging", "partial", null))
    val first = store.observePublishedCheckpoints(scope, prefix, limit = 2).first()
    assertEquals(listOf(prefix + "a", prefix + "b"), first.map { it.handle.partition })
    assertTrue(first.all { it.published })
    val last =
        store.observePublishedCheckpoints(scope, prefix, first.last().handle.partition, 2).first()
    assertEquals(listOf(prefix + "c"), last.map { it.handle.partition })
    assertTrue(
        store
            .observePublishedCheckpoints(scope, prefix, last.single().handle.partition)
            .first()
            .isEmpty()
    )
    assertTrue(
        store
            .observePublishedCheckpoints(scope.copy(accountKey = "other"), prefix)
            .first()
            .isEmpty()
    )
    val values = Channel<List<ReplicaCheckpoint>>(Channel.UNLIMITED)
    val observer =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.observePublishedCheckpoints(scope, prefix).collect(values::send)
        }
    try {
        val original = values.receive()
        val replacement = ReplicaHandle(scope, prefix + "a", "replacement")
        assertTrue(
            store.begin(scope, replacement.partition, replacement.generation, "staging", "first")
        )
        assertEquals(original, store.observePublishedCheckpoints(scope, prefix).first())
        assertTrue(values.tryReceive().isFailure)
        assertTrue(store.commit(replacement, 0, "complete", emptyList(), true))
        val published = values.receive()
        assertEquals("complete", published.first().payload)
        assertTrue(store.clear(scope, prefix + "b"))
        assertEquals(
            listOf(prefix + "a", prefix + "c"),
            values.receive().map { it.handle.partition },
        )
        database.accounts.activate(null)
        assertTrue(values.receive().isEmpty())
        val resumed = scope.copy(sessionId = "page-renewed")
        database.accounts.activate(resumed.sessionId, resumed.accountKey)
        assertEquals(2, store.observePublishedCheckpoints(resumed, prefix).first().size)
        assertTrue(store.observePublishedCheckpoints(scope, prefix).first().isEmpty())
        observer.cancelAndJoin()
        assertTrue(store.clear(resumed, prefix + "a"))
        assertTrue(values.tryReceive().isFailure)
        for (limit in listOf(0, 201, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                store.observePublishedCheckpoints(resumed, prefix, limit = limit).first()
            }
        }
        for (after in listOf("other-company/a", prefix + "a".repeat(160), prefix + "\n")) {
            assertFailsWith<IllegalArgumentException> {
                store.observePublishedCheckpoints(resumed, prefix, after).first()
            }
        }
    } finally {
        observer.cancelAndJoin()
        values.close()
    }
}
