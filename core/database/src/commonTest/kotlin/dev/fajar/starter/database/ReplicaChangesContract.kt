package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*

private class ReplicaChangesFixture(val db: AppDatabase) {
    val scope = ReplicaScope("change-session", "change-account")
    val partition = "change-source"
    val documents =
        (0..4).map { index ->
            ReplicaDocument(
                ('a' + index).toString(),
                900,
                "sort-$index",
                "body-$index",
                setOf("visible"),
            )
        }

    suspend fun seed(values: List<ReplicaDocument> = documents) {
        db.accounts.activate(scope.sessionId, scope.accountKey)
        assertTrue(
            db.replicas.replace(
                scope,
                partition,
                null,
                ReplicaSnapshot("original", "source", values),
            )
        )
    }

    suspend fun head() = assertNotNull(db.replicas.checkpoint(scope, partition))

    suspend fun read(cursor: ReplicaChangeCursor? = null, limit: Int = 50) =
        assertIs<ReplicaChangePage.Items>(
            db.replicas.changedDocuments(scope, partition, cursor, limit)
        )

    suspend fun after(revision: Long) = ReplicaChangeCursor.After(head().handle, revision)

    suspend fun write(values: List<ReplicaDocument>) {
        val expected = head()
        assertTrue(db.replicas.commit(expected.handle, expected.revision, "source", values, true))
    }
}

suspend fun verifyReplicaChangePaging(db: AppDatabase) {
    val f = ReplicaChangesFixture(db)
    f.seed()
    val first = f.read(limit = 2)
    assertEquals(f.documents.take(2), first.records.map { it.document })
    assertTrue(first.records.all { it.changedAtRevision == 0L })
    val second = f.read(assertNotNull(first.next), 2)
    assertEquals(first.window, second.window)
    assertEquals(f.documents.drop(2).take(2), second.records.map { it.document })
    val last = f.read(assertNotNull(second.next), 2)
    assertEquals(listOf(f.documents.last()), last.records.map { it.document })
    assertNull(last.next)
    assertTrue(f.read(f.after(0)).records.isEmpty())
}

suspend fun verifyReplicaChangeLocalRevision(db: AppDatabase) {
    val f = ReplicaChangesFixture(db)
    f.seed()
    val changed =
        f.documents.first().copy(payload = "edited", sortKey = "moved", tags = setOf("hidden"))
    f.write(listOf(changed))
    val page = f.read(f.after(0))
    assertEquals(listOf(ReplicaChangedDocument(changed, 1)), page.records)
    assertEquals(900L, page.records.single().document.version)
    f.write(emptyList())
    f.write(listOf(changed))
    assertEquals(3L, f.head().revision)
    assertTrue(f.read(f.after(1)).records.isEmpty())
}

suspend fun verifyReplicaChangeTombstones(db: AppDatabase) {
    val f = ReplicaChangesFixture(db)
    f.seed()
    f.write(listOf(f.documents.first().copy(version = 899, payload = "old")))
    assertTrue(f.read(f.after(0)).records.isEmpty())
    val deleted = f.documents.first().copy(payload = null, tags = emptySet())
    f.write(listOf(deleted))
    assertEquals(listOf(ReplicaChangedDocument(deleted, 2)), f.read(f.after(0)).records)
    f.write(listOf(f.documents.first()))
    assertTrue(f.read(f.after(2)).records.isEmpty())
    assertNull(db.replicas.document(f.scope, f.partition, deleted.id))
}

suspend fun verifyReplicaChangeWindowDuringWrites(db: AppDatabase) {
    val f = ReplicaChangesFixture(db)
    f.seed()
    val first = f.read(limit = 2)
    val changes =
        listOf(
            f.documents[0].copy(payload = "new-a"),
            f.documents[2].copy(payload = "new-c"),
            ReplicaDocument("aa", 7, "between", "new-between"),
        )
    f.write(changes)
    val rest = f.read(assertNotNull(first.next), 10)
    assertEquals(0L, rest.window.throughRevision)
    assertEquals(1L, rest.checkpoint.revision)
    assertEquals(f.documents.drop(3), rest.records.map { it.document })
    assertNull(rest.next)
    val nextWindow =
        f.read(ReplicaChangeCursor.After(first.window.handle, first.window.throughRevision))
    assertEquals(changes.sortedBy { it.id }, nextWindow.records.map { it.document })
    assertTrue(nextWindow.records.all { it.changedAtRevision == 1L })
}

suspend fun verifyReplicaChangePublishedGeneration(db: AppDatabase) {
    val f = ReplicaChangesFixture(db)
    f.seed()
    val after = f.after(0)
    assertTrue(db.replicas.begin(f.scope, f.partition, "next", "staging", "original"))
    val staging = f.head()
    val next = ReplicaDocument("next", 17, "next-sort", "next-body")
    assertTrue(db.replicas.commit(staging.handle, 0, "staging", listOf(next), false))
    val stillPublished = f.read(after)
    assertEquals("original", stillPublished.checkpoint.handle.generation)
    assertTrue(stillPublished.records.isEmpty())
    assertTrue(db.replicas.commit(staging.handle, 1, "published", emptyList(), true))
    assertEquals(
        ReplicaChangePage.Restart,
        db.replicas.changedDocuments(f.scope, f.partition, after),
    )
    assertEquals(listOf(ReplicaChangedDocument(next, 1)), f.read().records)
}

suspend fun verifyReplicaChangeOwnership(db: AppDatabase) {
    val f = ReplicaChangesFixture(db)
    f.seed()
    val after = f.after(0)
    db.accounts.activate("other-session", "other-account")
    assertEquals(ReplicaChangePage.Absent, db.replicas.changedDocuments(f.scope, f.partition))
    assertEquals(
        ReplicaChangePage.Restart,
        db.replicas.changedDocuments(f.scope, f.partition, after),
    )
    val renewed = f.scope.copy(sessionId = "renewed-session")
    db.accounts.activate(renewed.sessionId, renewed.accountKey)
    val retained =
        assertIs<ReplicaChangePage.Items>(db.replicas.changedDocuments(renewed, f.partition))
    assertEquals(f.documents, retained.records.map { it.document })
    assertFailsWith<IllegalArgumentException> {
        db.replicas.changedDocuments(renewed, f.partition, after)
    }
}

suspend fun verifyReplicaChangeBoundsAndAbsence(db: AppDatabase) {
    val f = ReplicaChangesFixture(db)
    db.accounts.activate(f.scope.sessionId, f.scope.accountKey)
    assertEquals(ReplicaChangePage.Absent, db.replicas.changedDocuments(f.scope, f.partition))
    val handle = ReplicaHandle(f.scope, f.partition, "missing")
    assertEquals(
        ReplicaChangePage.Restart,
        db.replicas.changedDocuments(f.scope, f.partition, ReplicaChangeCursor.After(handle, 0)),
    )
    for (limit in listOf(0, 201)) assertFailsWith<IllegalArgumentException> {
        db.replicas.changedDocuments(f.scope, f.partition, limit = limit)
    }
    for (cursor in
        listOf(
            ReplicaChangeCursor.After(handle, -1),
            ReplicaChangeCursor.After(handle.copy(partition = "other"), 0),
            ReplicaChangeCursor.Within(
                ReplicaChangeWindow(handle, 3, 2),
                ReplicaChangePosition(2, "item"),
            ),
            ReplicaChangeCursor.Within(
                ReplicaChangeWindow(handle, 1, 2),
                ReplicaChangePosition(1, "item"),
            ),
            ReplicaChangeCursor.Within(
                ReplicaChangeWindow(handle, null, 2),
                ReplicaChangePosition(2, "invalid id"),
            ),
        )) assertFailsWith<IllegalArgumentException> {
        db.replicas.changedDocuments(f.scope, f.partition, cursor)
    }
    assertNull(db.replicas.checkpoint(f.scope, f.partition))
}

suspend fun verifyReplicaChangeMaximumPage(db: AppDatabase) {
    val f = ReplicaChangesFixture(db)
    val initial =
        (0..199).map {
            ReplicaDocument(
                "id-" + it.toString().padStart(3, '0'),
                it.toLong(),
                "sort-$it",
                "value-$it",
            )
        }
    f.seed(initial)
    val last = ReplicaDocument("last", 0, "last-sort", "last-value")
    f.write(listOf(last))
    val first = f.read(limit = 200)
    assertEquals(initial, first.records.map { it.document })
    val next = f.read(assertNotNull(first.next), 200)
    assertEquals(listOf(ReplicaChangedDocument(last, 1)), next.records)
    assertNull(next.next)
}

suspend fun verifyReplicaChangeAtomicRollback(db: AppDatabase) {
    val f = ReplicaChangesFixture(db)
    f.seed()
    assertTrue(
        db.replicas.replace(f.scope, "other", null, ReplicaSnapshot("other", "other", emptyList()))
    )
    val first = f.head()
    val other = assertNotNull(db.replicas.checkpoint(f.scope, "other"))
    assertFalse(
        db.replicas.mutate(
            f.scope,
            listOf(
                ReplicaMutation.Commit(
                    first.handle,
                    first.revision,
                    "changed",
                    listOf(f.documents.first().copy(payload = "changed")),
                    true,
                ),
                ReplicaMutation.Commit(
                    other.handle,
                    other.revision + 1,
                    "conflict",
                    emptyList(),
                    true,
                ),
            ),
        )
    )
    assertEquals(first, f.head())
    assertTrue(f.read(f.after(0)).records.isEmpty())
}

suspend fun verifyReplicaChangeCompetingWriters(db: AppDatabase) = coroutineScope {
    val f = ReplicaChangesFixture(db)
    f.seed()
    val expected = f.head()
    val writes =
        listOf("left", "right")
            .map { value ->
                async {
                    value to
                        db.replicas.commit(
                            expected.handle,
                            expected.revision,
                            value,
                            listOf(f.documents.first().copy(payload = value)),
                            true,
                        )
                }
            }
            .awaitAll()
    val winner = writes.single { it.second }.first
    val changes = f.read(f.after(0))
    assertEquals(
        listOf(ReplicaChangedDocument(f.documents.first().copy(payload = winner), 1)),
        changes.records,
    )
    assertEquals(winner, changes.checkpoint.payload)
}

suspend fun verifyReplicaChangeCancellation(db: AppDatabase) = coroutineScope {
    val f = ReplicaChangesFixture(db)
    f.seed()
    val expected = f.head()
    val changed = f.documents.first().copy(payload = "cancelled-write")
    val write =
        async(start = CoroutineStart.UNDISPATCHED) {
            db.replicas.commit(expected.handle, expected.revision, "changed", listOf(changed), true)
        }
    write.cancelAndJoin()
    val page = f.read(f.after(0))
    assertEquals(
        if (page.checkpoint.revision == 0L) emptyList()
        else listOf(ReplicaChangedDocument(changed, 1)),
        page.records,
    )
    assertEquals(
        if (page.records.isEmpty()) f.documents.first() else changed,
        db.replicas.document(f.scope, f.partition, changed.id),
    )
}

suspend fun prepareReplicaChangeRestart(db: AppDatabase) {
    val f = ReplicaChangesFixture(db)
    f.seed()
    f.write(listOf(f.documents.first().copy(payload = "retained")))
}

suspend fun verifyReplicaChangeRestart(db: AppDatabase) {
    val f = ReplicaChangesFixture(db)
    assertEquals(
        listOf(ReplicaChangedDocument(f.documents.first().copy(payload = "retained"), 1)),
        f.read(f.after(0)).records,
    )
}
