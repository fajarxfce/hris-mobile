package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

suspend fun verifyReplicaCheckpointObservation(database: AppDatabase) = coroutineScope {
    val store = database.replicas
    val handle = ReplicaHandle(ReplicaScope("checkpoint-session"), "company-a/inbox", "generation")
    database.accounts.activate(handle.scope.sessionId)
    val checkpoints = Channel<ReplicaCheckpoint?>(Channel.UNLIMITED)
    val observer =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.observeCheckpoint(handle.scope, handle.partition).collect(checkpoints::send)
        }
    try {
        assertNull(checkpoints.receive())
        assertTrue(store.begin(handle.scope, handle.partition, handle.generation, "start", null))
        val staging = checkpoints.receive()!!
        assertEquals(handle, staging.handle)
        assertFalse(staging.published)
        assertEquals("start", staging.payload)
        assertTrue(
            store.commit(handle, 0, "next", listOf(ReplicaDocument("a", 1, "a", "sealed")), false)
        )
        assertEquals(1, checkpoints.receive()!!.revision)
        assertNull(store.observe(handle.scope, handle.partition).first().checkpoint)
        assertTrue(store.commit(handle, 1, "ready", emptyList(), true))
        val published = checkpoints.receive()!!
        assertTrue(published.published)
        assertEquals("ready", published.payload)
        assertNull(
            store.observeCheckpoint(ReplicaScope("another-session"), handle.partition).first()
        )
        assertNull(store.observeCheckpoint(handle.scope, "company-b/inbox").first())
        assertTrue(store.clear(handle.scope, handle.partition))
        assertNull(checkpoints.receive())
        assertTrue(store.begin(handle.scope, handle.partition, "replacement", "new", null))
        assertEquals("replacement", checkpoints.receive()!!.handle.generation)
        database.accounts.activate("replacement-session")
        assertNull(checkpoints.receive())
        observer.cancelAndJoin()
        assertTrue(
            store.begin(
                ReplicaScope("replacement-session"),
                handle.partition,
                "other",
                "other",
                null,
            )
        )
        assertTrue(checkpoints.tryReceive().isFailure)
    } finally {
        observer.cancelAndJoin()
        checkpoints.close()
    }
}
