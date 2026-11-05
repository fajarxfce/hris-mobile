package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private val stagingScope = ReplicaScope("staging-session", "staging-account")
private const val stagingPartition = "staging-company/summaries"
private val originalDocuments =
    listOf(
        ReplicaDocument("kept", 3, "kept", "original", setOf("retained")),
        ReplicaDocument("removed", 4, "removed", null),
    )
private val incomingDocuments =
    listOf(
        ReplicaDocument("kept", 5, "kept", "incoming", setOf("incoming")),
        ReplicaDocument("new", 0, "new", "new-record"),
        ReplicaDocument("removed", 7, "removed", null),
    )

private suspend fun prepareStaging(database: AppDatabase): ReplicaCheckpoint {
    database.accounts.activate(stagingScope.sessionId, stagingScope.accountKey)
    val store = database.replicas
    assertTrue(
        store.replace(
            stagingScope,
            stagingPartition,
            null,
            ReplicaSnapshot("active", "active-cursor", originalDocuments),
        )
    )
    assertTrue(store.begin(stagingScope, stagingPartition, "staged", "first-page", "active"))
    val initial = assertNotNull(store.checkpoint(stagingScope, stagingPartition))
    assertTrue(
        store.commit(initial.handle, initial.revision, "next-page", incomingDocuments, false)
    )
    return assertNotNull(store.checkpoint(stagingScope, stagingPartition))
}

suspend fun verifyWorkingReplicaDocuments(database: AppDatabase) {
    val stage = prepareStaging(database)
    val store = database.replicas
    val working =
        store.workingDocuments(
            stagingScope,
            stagingPartition,
            listOf("removed", "missing", "kept", "new"),
        )
    assertEquals(stage, working.checkpoint)
    assertEquals(incomingDocuments, working.documents)
    assertEquals(
        ReplicaPage(stage, emptyList()),
        store.workingDocuments(stagingScope, stagingPartition, emptyList()),
    )
    val visible = store.observe(stagingScope, stagingPartition).first()
    assertEquals("active-cursor", visible.checkpoint?.payload)
    assertEquals(listOf(originalDocuments.first()), visible.documents)
    assertNull(store.document(stagingScope, stagingPartition, "new"))
    assertNull(store.document(stagingScope, stagingPartition, "removed"))
    assertTrue(store.commit(stage.handle, stage.revision, "completed", emptyList(), true))
    val active =
        store.workingDocuments(stagingScope, stagingPartition, listOf("kept", "removed", "new"))
    assertTrue(assertNotNull(active.checkpoint).published)
    assertEquals(incomingDocuments, active.documents)
    assertEquals(
        incomingDocuments.filter { it.payload != null },
        store.observe(stagingScope, stagingPartition).first().documents,
    )

    database.accounts.activate("another-session", "another-account")
    assertEquals(
        ReplicaPage(null, emptyList()),
        store.workingDocuments(stagingScope, stagingPartition, listOf("kept")),
    )
    assertEquals(
        ReplicaPage(null, emptyList()),
        store.workingDocuments(
            ReplicaScope("another-session", "another-account"),
            stagingPartition,
            listOf("kept"),
        ),
    )
    assertFalse(store.discardStaging(stagingScope, stagingPartition, stage))
    val resumed = stagingScope.copy(sessionId = "same-account-new-session")
    database.accounts.activate(resumed.sessionId, resumed.accountKey)
    assertEquals(
        incomingDocuments,
        store
            .workingDocuments(resumed, stagingPartition, listOf("new", "removed", "kept"))
            .documents,
    )
}

suspend fun verifyReplicaStagingDiscard(database: AppDatabase) {
    val stage = prepareStaging(database)
    val store = database.replicas
    val original = store.observe(stagingScope, stagingPartition).first()
    val other = "unrelated/summaries"
    assertTrue(
        store.replace(
            stagingScope,
            other,
            null,
            ReplicaSnapshot("unrelated", "unrelated", originalDocuments),
        )
    )
    assertFalse(
        store.discardStaging(
            stagingScope,
            stagingPartition,
            stage.copy(revision = stage.revision + 1),
        )
    )
    assertFalse(
        store.discardStaging(stagingScope, stagingPartition, stage.copy(payload = "stale-cursor"))
    )
    assertFalse(
        store.discardStaging(stagingScope, stagingPartition, assertNotNull(original.checkpoint))
    )
    assertEquals(stage, store.checkpoint(stagingScope, stagingPartition))
    assertTrue(store.discardStaging(stagingScope, stagingPartition, stage))
    assertFalse(store.discardStaging(stagingScope, stagingPartition, stage))
    assertEquals(original, store.observe(stagingScope, stagingPartition).first())
    assertEquals(original.checkpoint, store.checkpoint(stagingScope, stagingPartition))
    assertEquals(
        originalDocuments,
        store
            .workingDocuments(stagingScope, stagingPartition, listOf("removed", "kept", "new"))
            .documents,
    )
    assertFalse(
        store.commit(stage.handle, stage.revision, "late-background", incomingDocuments, true)
    )
    val active = assertNotNull(original.checkpoint)
    val immediate = ReplicaDocument("immediate", 0, "immediate", "opened-now")
    assertTrue(store.commit(active.handle, active.revision, "interactive", listOf(immediate), true))
    assertEquals(
        listOf(immediate, originalDocuments.first()),
        store.observe(stagingScope, stagingPartition).first().documents,
    )
    assertEquals(
        listOf(originalDocuments.first()),
        store.observe(stagingScope, other).first().documents,
    )
    // Partial replacement tags were removed with their generation, and active tags remain.
    assertEquals(
        listOf(emptyList(), listOf(originalDocuments.first())),
        store
            .observeQueries(
                stagingScope,
                stagingPartition,
                listOf(ReplicaQuery("incoming"), ReplicaQuery("retained")),
            )
            .first()
            .pages,
    )

    val fresh = "initial/summaries"
    assertTrue(store.begin(stagingScope, fresh, "initial", "initial", null))
    val initial = assertNotNull(store.checkpoint(stagingScope, fresh))
    assertTrue(store.commit(initial.handle, initial.revision, "partial", incomingDocuments, false))
    assertTrue(
        store.discardStaging(
            stagingScope,
            fresh,
            assertNotNull(store.checkpoint(stagingScope, fresh)),
        )
    )
    assertNull(store.checkpoint(stagingScope, fresh))
    assertEquals(
        ReplicaPage(null, emptyList()),
        store.workingDocuments(stagingScope, fresh, listOf("new")),
    )
    assertFalse(store.commit(initial.handle, 1L, "late-first-page", emptyList(), true))
    assertTrue(store.begin(stagingScope, fresh, "restarted", "restarted", null))
}

suspend fun verifyReplicaStagingRaces(database: AppDatabase) = coroutineScope {
    var stage = prepareStaging(database)
    val store = database.replicas
    // Each pair of competing transactions must have exactly one winner.
    for (publish in listOf(false, true)) {
        val discard = async { store.discardStaging(stagingScope, stagingPartition, stage) }
        val advance = async {
            store.commit(stage.handle, stage.revision, "race-complete", incomingDocuments, publish)
        }
        val discarded = discard.await()
        assertNotEquals(discarded, advance.await())
        val visible = store.observe(stagingScope, stagingPartition).first()
        assertEquals(
            if (!discarded && publish) "race-complete" else "active-cursor",
            visible.checkpoint?.payload,
        )
        assertEquals(
            if (!discarded && publish) incomingDocuments.filter { it.payload != null }
            else originalDocuments.filter { it.payload != null },
            visible.documents,
        )
        assertFalse(store.discardStaging(stagingScope, stagingPartition, stage))
        if (!publish) {
            val current = assertNotNull(store.checkpoint(stagingScope, stagingPartition))
            assertTrue(
                store.begin(
                    stagingScope,
                    stagingPartition,
                    "next-stage",
                    "next",
                    current.handle.generation,
                )
            )
            stage = assertNotNull(store.checkpoint(stagingScope, stagingPartition))
        }
    }
    val atomic = "atomic/summaries"
    assertTrue(store.begin(stagingScope, atomic, "atomic", "0", null))
    val handle = assertNotNull(store.checkpoint(stagingScope, atomic)).handle
    val writer = async {
        for (index in 1..30) {
            val docs =
                listOf(
                    ReplicaDocument("a", index.toLong(), "a", "sealed-$index"),
                    ReplicaDocument("b", index.toLong(), "b", null),
                )
            assertTrue(store.commit(handle, index.toLong() - 1, "$index", docs, false))
            yield()
        }
    }
    repeat(30) {
        val read = store.workingDocuments(stagingScope, atomic, listOf("b", "a"))
        val epoch = assertNotNull(read.checkpoint).payload.toLong()
        if (epoch == 0L) assertTrue(read.documents.isEmpty())
        else {
            assertEquals(listOf("a", "b"), read.documents.map { it.id })
            assertTrue(read.documents.all { it.version == epoch })
            assertEquals("sealed-$epoch", read.documents.first().payload)
            assertNull(read.documents.last().payload)
        }
        yield()
    }
    writer.await()
}

suspend fun verifyReplicaStagingBoundsAndCancellation(database: AppDatabase) = coroutineScope {
    val stage = prepareStaging(database)
    val store = database.replicas
    for (ids in
        listOf(
            listOf("same", "same"),
            listOf("bad id"),
            listOf("x".repeat(129)),
            (1..201).map { "$it" },
        )) {
        assertFailsWith<IllegalArgumentException> {
            store.workingDocuments(stagingScope, stagingPartition, ids)
        }
    }
    val maximum = (1..200).map { "$it" }
    assertEquals(stage, store.workingDocuments(stagingScope, stagingPartition, maximum).checkpoint)
    assertFailsWith<IllegalArgumentException> {
        store.discardStaging(
            stagingScope,
            stagingPartition,
            stage.copy(handle = stage.handle.copy(partition = "foreign")),
        )
    }
    assertEquals(stage, store.checkpoint(stagingScope, stagingPartition))
    val original = store.observe(stagingScope, stagingPartition).first()
    val cancelledBefore = launch {
        currentCoroutineContext().cancel()
        store.discardStaging(stagingScope, stagingPartition, stage)
    }
    cancelledBefore.join()
    assertEquals(stage, store.checkpoint(stagingScope, stagingPartition))
    val discard =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.discardStaging(stagingScope, stagingPartition, stage)
        }
    yield()
    discard.cancelAndJoin()
    assertEquals(original, store.observe(stagingScope, stagingPartition).first())
    val after = assertNotNull(store.checkpoint(stagingScope, stagingPartition))
    assertTrue(after == stage || after == original.checkpoint)
    val working =
        store.workingDocuments(stagingScope, stagingPartition, listOf("kept", "removed", "new"))
    assertEquals(if (after.published) originalDocuments else incomingDocuments, working.documents)
}

suspend fun prepareReplicaStagingRestart(database: AppDatabase) {
    prepareStaging(database)
}

suspend fun verifyReplicaStagingAfterRestart(database: AppDatabase) {
    val resumed = stagingScope.copy(sessionId = "resumed-staging-session")
    database.accounts.activate(resumed.sessionId, resumed.accountKey)
    val store = database.replicas
    assertNull(store.workingDocuments(stagingScope, stagingPartition, listOf("new")).checkpoint)
    val working =
        store.workingDocuments(resumed, stagingPartition, listOf("new", "kept", "removed"))
    assertEquals(incomingDocuments, working.documents)
    assertFalse(assertNotNull(working.checkpoint).published)
    assertTrue(store.discardStaging(resumed, stagingPartition, working.checkpoint))
    assertEquals(
        originalDocuments,
        store
            .workingDocuments(resumed, stagingPartition, listOf("kept", "removed", "new"))
            .documents,
    )
    assertEquals(
        listOf(originalDocuments.first()),
        store.observe(resumed, stagingPartition).first().documents,
    )
}
