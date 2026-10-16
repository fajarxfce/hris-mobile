package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

suspend fun verifyPublishedReplicaCheckpoint(database: AppDatabase) = coroutineScope {
    val store = database.replicas
    val scope = ReplicaScope("published-session", "published-account")
    val first = ReplicaHandle(scope, "company/status", "published-first")
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val values = Channel<ReplicaCheckpoint?>(Channel.UNLIMITED)
    val observer =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.observePublishedCheckpoint(scope, first.partition).collect(values::send)
        }
    try {
        assertNull(values.receive())
        assertTrue(store.begin(scope, first.partition, first.generation, "initial", null))
        assertNull(store.observePublishedCheckpoint(scope, first.partition).first())
        assertTrue(store.commit(first, 0, "first-complete", emptyList(), true))
        val initial = assertNotNull(values.receive())
        assertTrue(initial.published)
        assertEquals(first, initial.handle)
        assertEquals("first-complete", initial.payload)

        val next = first.copy(generation = "published-next")
        assertTrue(
            store.begin(scope, next.partition, next.generation, "replacement", first.generation)
        )
        assertEquals(next, store.checkpoint(scope, first.partition)!!.handle)
        assertEquals(initial, store.observePublishedCheckpoint(scope, first.partition).first())
        assertTrue(
            store.commit(
                next,
                0,
                "partial",
                listOf(ReplicaDocument("a", 1, "a", "ciphertext")),
                false,
            )
        )
        assertEquals(initial, store.observePublishedCheckpoint(scope, first.partition).first())
        assertTrue(values.tryReceive().isFailure)
        assertTrue(store.commit(next, 1, "second-complete", emptyList(), true))
        val published = assertNotNull(values.receive())
        assertEquals(next, published.handle)
        assertEquals("second-complete", published.payload)
        assertEquals(2, published.revision)

        assertNull(store.observePublishedCheckpoint(scope, "another-company/status").first())
        assertNull(
            store
                .observePublishedCheckpoint(
                    scope.copy(accountKey = "another-account"),
                    first.partition,
                )
                .first()
        )
        database.accounts.activate(null)
        assertNull(values.receive())
        val renewed = scope.copy(sessionId = "published-renewed")
        database.accounts.activate(renewed.sessionId, renewed.accountKey)
        val retained =
            assertNotNull(store.observePublishedCheckpoint(renewed, first.partition).first())
        assertEquals(published.copy(handle = next.copy(scope = renewed)), retained)
        assertNull(store.observePublishedCheckpoint(scope, first.partition).first())
        assertFalse(store.clear(scope, first.partition))
        assertEquals(retained, store.observePublishedCheckpoint(renewed, first.partition).first())
        observer.cancelAndJoin()
        assertTrue(store.clear(renewed, first.partition))
        assertNull(store.observePublishedCheckpoint(renewed, first.partition).first())
        assertTrue(values.tryReceive().isFailure)
    } finally {
        observer.cancelAndJoin()
        values.close()
    }
}
