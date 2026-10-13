package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

suspend fun verifyReplicaQueryIndexes(database: AppDatabase) {
    val scope = ReplicaScope("query-session", "query-account")
    val partition = "query-company/records"
    val store = database.replicas
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val first = ReplicaDocument("a", 1, "01", "sealed-a", setOf("all", "pending"))
    val second = ReplicaDocument("b", 1, "02", "sealed-b", setOf("all", "approved"))
    val third = ReplicaDocument("c", 1, "03", "sealed-c", setOf("all", "pending"))
    assertTrue(
        store.replace(
            scope,
            partition,
            null,
            ReplicaSnapshot("initial", "page-1", listOf(first, second, third)),
        )
    )
    val queries =
        listOf(
            ReplicaQuery("pending", limit = 1),
            ReplicaQuery("approved"),
            ReplicaQuery("missing"),
            ReplicaQuery(),
        )
    val page = store.observeQueries(scope, partition, queries).first()
    assertEquals(
        listOf(listOf(first), listOf(second), emptyList(), listOf(first, second, third)),
        page.pages,
    )
    assertEquals("page-1", page.checkpoint?.payload)
    assertEquals(first, store.document(scope, partition, first.id))
    assertEquals(
        listOf(third),
        store
            .observeQueries(scope, partition, listOf(ReplicaQuery("pending", first.sortKey)))
            .first()
            .pages
            .single(),
    )

    // Updating the resource replaces its old index keys; an older response cannot restore them.
    val approved = first.copy(version = 2, payload = "accepted", tags = setOf("all", "approved"))
    val handle = requireNotNull(page.checkpoint).handle
    assertTrue(store.commit(handle, 0, "page-2", listOf(approved), true))
    assertTrue(store.commit(handle, 1, "page-3", listOf(first), true))
    val changed = store.observeQueries(scope, partition, queries).first()
    assertEquals(listOf(third), changed.pages[0])
    assertEquals(listOf(approved, second), changed.pages[1])
    assertEquals(approved, changed.pages[3].first())

    // A deletion removes every index and wins against an equal-version late hydration.
    assertTrue(
        store.commit(
            handle,
            2,
            "page-4",
            listOf(approved.copy(payload = null, tags = emptySet())),
            true,
        )
    )
    assertTrue(store.commit(handle, 3, "page-5", listOf(approved), true))
    assertEquals(listOf(second), store.observeQueries(scope, partition, queries).first().pages[1])
    assertTrue(store.commit(handle, 4, "page-6", listOf(approved.copy(version = 3)), true))
    assertEquals(2, store.observeQueries(scope, partition, queries).first().pages[1].size)

    // A conflicting sort key rolls back index changes and the cursor together.
    val before = store.observeQueries(scope, partition, queries).first()
    assertFails {
        store.commit(
            handle,
            5,
            "invalid",
            listOf(second.copy(tags = setOf("changed")), third.copy(sortKey = second.sortKey)),
            true,
        )
    }
    assertEquals(before, store.observeQueries(scope, partition, queries).first())
    assertTrue(
        store
            .observeQueries(scope, partition, listOf(ReplicaQuery("changed")))
            .first()
            .pages
            .single()
            .isEmpty()
    )
}

suspend fun verifyReplicaQueryPublication(database: AppDatabase) = coroutineScope {
    val scope = ReplicaScope("query-observer", "query-owner")
    val partition = "query-observer/records"
    val store = database.replicas
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val queries = listOf(ReplicaQuery("left"), ReplicaQuery("right"))
    val values = Channel<ReplicaQueryPage>(8)
    val observer =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.observeQueries(scope, partition, queries).collect(values::send)
        }
    try {
        assertEquals(listOf(emptyList(), emptyList()), values.receive().pages)
        val initial =
            listOf(
                ReplicaDocument("left", 1, "left", "left-1", setOf("left")),
                ReplicaDocument("right", 1, "right", "right-1", setOf("right")),
            )
        assertTrue(
            store.replace(scope, partition, null, ReplicaSnapshot("published", "cursor-1", initial))
        )
        val first = values.receive()
        assertEquals(listOf(listOf(initial[0]), listOf(initial[1])), first.pages)
        assertTrue(store.begin(scope, partition, "replacement", "start", "published"))
        val staged = ReplicaHandle(scope, partition, "replacement")
        val replacements = initial.map { it.copy(version = 2, payload = it.id + "-2") }
        assertTrue(store.commit(staged, 0, "half", listOf(replacements[0]), false))
        assertEquals(first, store.observeQueries(scope, partition, queries).first())
        assertTrue(store.commit(staged, 1, "cursor-2", listOf(replacements[1]), true))
        val complete = values.receive()
        assertEquals("cursor-2", complete.checkpoint?.payload)
        assertEquals(listOf(listOf(replacements[0]), listOf(replacements[1])), complete.pages)

        val resumed = scope.copy(sessionId = "query-new-login")
        database.accounts.activate(resumed.sessionId, resumed.accountKey)
        assertNull(values.receive().checkpoint)
        assertEquals(
            complete.pages,
            store.observeQueries(resumed, partition, queries).first().pages,
        )
        assertFalse(store.commit(staged, 2, "late", emptyList(), true))
        assertNull(
            store
                .observeQueries(resumed.copy(accountKey = "wrong-owner"), partition, queries)
                .first()
                .checkpoint
        )
        assertNull(store.observeQueries(resumed, "another-company", queries).first().checkpoint)
        observer.cancelAndJoin()
        store.clear(resumed, partition)
        assertTrue(values.tryReceive().isFailure)
    } finally {
        observer.cancelAndJoin()
        values.close()
    }
}

suspend fun verifyReplicaQueryBounds(database: AppDatabase) {
    val scope = ReplicaScope("query-bounds", "query-bounds-owner")
    val store = database.replicas
    database.accounts.activate(scope.sessionId, scope.accountKey)
    for (queries in
        listOf(
            emptyList(),
            List(5) { ReplicaQuery(limit = 1) },
            listOf(ReplicaQuery(limit = 200), ReplicaQuery(limit = 1)),
            listOf(ReplicaQuery("")),
            listOf(ReplicaQuery("private filter")),
            listOf(ReplicaQuery(after = "bad cursor")),
            listOf(ReplicaQuery(limit = 0)),
        )) {
        assertFailsWith<IllegalArgumentException> {
            store.observeQueries(scope, "bounds", queries).first()
        }
    }
    val valid = ReplicaDocument("one", 0, "one", "ciphertext", setOf("valid"))
    for (document in
        listOf(
            valid.copy(tags = setOf("")),
            valid.copy(tags = setOf("private filter")),
            valid.copy(tags = setOf("t".repeat(129))),
            valid.copy(tags = (0..16).map { "tag-$it" }.toSet()),
            valid.copy(payload = null),
        )) {
        assertFailsWith<IllegalArgumentException> {
            store.replace(
                scope,
                "bounds",
                null,
                ReplicaSnapshot("new", "checkpoint", listOf(document)),
            )
        }
        assertNull(store.checkpoint(scope, "bounds"))
    }
}
