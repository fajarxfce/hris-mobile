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
    val handle = ReplicaHandle("checkpoint-session", "company-a/inbox", "generation")
    database.accounts.activate(handle.sessionId)
    val checkpoints = Channel<ReplicaCheckpoint?>(Channel.UNLIMITED)
    val observer =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.observeCheckpoint(handle.sessionId, handle.partition).collect(checkpoints::send)
        }
    try {
        assertNull(checkpoints.receive())
        assertTrue(
            store.begin(handle.sessionId, handle.partition, handle.generation, "start", null)
        )
        val staging = checkpoints.receive()!!
        assertEquals(handle, staging.handle)
        assertFalse(staging.published)
        assertEquals("start", staging.payload)
        assertTrue(
            store.commit(handle, 0, "next", listOf(ReplicaDocument("a", 1, "a", "sealed")), false)
        )
        assertEquals(1, checkpoints.receive()!!.revision)
        assertNull(store.observe(handle.sessionId, handle.partition).first().checkpoint)
        assertTrue(store.commit(handle, 1, "ready", emptyList(), true))
        val published = checkpoints.receive()!!
        assertTrue(published.published)
        assertEquals("ready", published.payload)
        assertNull(store.observeCheckpoint("another-session", handle.partition).first())
        assertNull(store.observeCheckpoint(handle.sessionId, "company-b/inbox").first())
        assertTrue(store.clear(handle.sessionId, handle.partition))
        assertNull(checkpoints.receive())
        assertTrue(store.begin(handle.sessionId, handle.partition, "replacement", "new", null))
        assertEquals("replacement", checkpoints.receive()!!.handle.generation)
        database.accounts.activate("replacement-session")
        assertNull(checkpoints.receive())
        observer.cancelAndJoin()
        assertTrue(store.begin("replacement-session", handle.partition, "other", "other", null))
        assertTrue(checkpoints.tryReceive().isFailure)
    } finally {
        observer.cancelAndJoin()
        checkpoints.close()
    }
}
