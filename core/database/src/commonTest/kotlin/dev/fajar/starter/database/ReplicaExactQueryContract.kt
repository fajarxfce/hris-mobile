package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

suspend fun verifyReplicaExactSelections(database: AppDatabase) {
    val scope = ReplicaScope("exact-session", "exact-account")
    val store = database.replicas
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val records =
        (0..199).map {
            ReplicaDocument("id-$it", 1, it.toString().padStart(3, '0'), "sealed-$it", setOf("all"))
        }
    assertTrue(store.replace(scope, "exact", null, ReplicaSnapshot("first", "initial", records)))
    val queries =
        listOf(
            ReplicaQuery(id = "id-199", limit = 1),
            ReplicaQuery(id = "199", limit = 1),
            ReplicaQuery("all", before = "199", limit = 2),
            ReplicaQuery(id = "id-0", limit = 1),
        )
    val first = store.observeQueries(scope, "exact", queries).first()
    assertEquals(
        listOf(
            listOf(records[199]),
            emptyList(),
            listOf(records[198], records[197]),
            listOf(records[0]),
        ),
        first.pages,
    )
    val handle = requireNotNull(first.checkpoint).handle
    assertTrue(
        store.commit(
            handle,
            0,
            "deleted",
            listOf(records[199].copy(version = 2, payload = null, tags = emptySet())),
            true,
        )
    )
    val deleted = store.observeQueries(scope, "exact", queries).first()
    assertEquals("deleted", deleted.checkpoint?.payload)
    assertTrue(deleted.pages[0].isEmpty())
    assertEquals(listOf(records[198], records[197]), deleted.pages[2])
    val revived = records[199].copy(version = 3, sortKey = "300", payload = "revived")
    assertTrue(store.commit(handle, 1, "revived", listOf(revived), true))
    assertEquals(listOf(revived), store.observeQueries(scope, "exact", queries).first().pages[0])
    assertEquals(
        listOf(revived),
        store
            .observeQueries(scope, "exact", listOf(ReplicaQuery(id = revived.id)))
            .first()
            .pages
            .single(),
    )
}

suspend fun verifyReplicaExactPublication(database: AppDatabase) = coroutineScope {
    val scope = ReplicaScope("exact-observer", "exact-owner")
    val store = database.replicas
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val queries =
        listOf(
            ReplicaQuery(id = "draft", limit = 1),
            ReplicaQuery(id = "marker", limit = 1),
            ReplicaQuery("pending", limit = 2),
        )
    val values = Channel<ReplicaQueryPage>(8)
    val observer =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.observeQueries(scope, "observed", queries).collect(values::send)
        }
    try {
        assertEquals(ReplicaQueryPage(null, List(3) { emptyList() }), values.receive())
        val original =
            listOf(
                ReplicaDocument("draft", 0, "z", "draft-1"),
                ReplicaDocument("marker", 0, "y", "marker-1"),
                ReplicaDocument("submission", 0, "a", "pending-1", setOf("pending")),
            )
        assertTrue(store.replace(scope, "observed", null, ReplicaSnapshot("old", "one", original)))
        val old = values.receive()
        assertEquals(original.map { listOf(it) }, old.pages)
        assertTrue(store.begin(scope, "observed", "new", "staging", "old"))
        val staged = ReplicaHandle(scope, "observed", "new")
        val replacements = original.map { it.copy(version = 1, payload = it.payload + "-new") }
        assertTrue(store.commit(staged, 0, "partial", replacements.take(2), false))
        assertEquals(old, store.observeQueries(scope, "observed", queries).first())
        assertTrue(store.commit(staged, 1, "complete", replacements.takeLast(1), true))
        val complete = values.receive()
        assertEquals("complete", complete.checkpoint?.payload)
        assertEquals(replacements.map { listOf(it) }, complete.pages)
        val resumed = scope.copy(sessionId = "new-login")
        database.accounts.activate(resumed.sessionId, resumed.accountKey)
        assertNull(values.receive().checkpoint)
        assertEquals(
            complete.pages,
            store.observeQueries(resumed, "observed", queries).first().pages,
        )
        assertNull(
            store
                .observeQueries(resumed.copy(accountKey = "foreign"), "observed", queries)
                .first()
                .checkpoint
        )
        assertNull(store.observeQueries(resumed, "other-partition", queries).first().checkpoint)
        observer.cancelAndJoin()
        assertTrue(store.clear(resumed, "observed"))
        assertTrue(values.tryReceive().isFailure)
    } finally {
        observer.cancelAndJoin()
        values.cancel()
    }
}

suspend fun verifyReplicaExactBounds(database: AppDatabase) {
    val scope = ReplicaScope("exact-bounds", "exact-bounds-owner")
    val store = database.replicas
    database.accounts.activate(scope.sessionId, scope.accountKey)
    for (query in
        listOf(
            ReplicaQuery(id = ""),
            ReplicaQuery(id = "has space"),
            ReplicaQuery(id = "a".repeat(129)),
            ReplicaQuery(id = "one", tag = "all"),
            ReplicaQuery(id = "one", after = "0"),
            ReplicaQuery(id = "one", before = "z"),
            ReplicaQuery(id = "one", limit = 0),
            ReplicaQuery(id = "one", limit = 201),
        )) assertFailsWith<IllegalArgumentException> {
        store.observeQueries(scope, "bounds", listOf(query)).first()
    }
    assertNull(store.checkpoint(scope, "bounds"))
    assertEquals(
        ReplicaQuery("pending"),
        Json.decodeFromString<ReplicaQuery>("{\"tag\":\"pending\"}"),
    )
    val exact = ReplicaQuery(id = "one", limit = 1)
    assertEquals(exact, Json.decodeFromString<ReplicaQuery>(Json.encodeToString(exact)))
    val document = ReplicaDocument("one", 0, "unrelated-sort", "sealed")
    assertTrue(
        store.replace(
            scope,
            "bounds",
            null,
            ReplicaSnapshot("visible", "retained", listOf(document)),
        )
    )
    val supplied = mutableListOf(exact)
    val observed = store.observeQueries(scope, "bounds", supplied)
    supplied[0] = ReplicaQuery(id = "missing", limit = 1)
    supplied.clear()
    assertEquals(listOf(listOf(document)), observed.first().pages)
}

suspend fun prepareReplicaExactRestart(database: AppDatabase) {
    val scope = ReplicaScope("exact-restart", "exact-restart-owner")
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val documents =
        listOf(
            ReplicaDocument("draft", 0, "z", "persisted-draft"),
            ReplicaDocument("marker", 0, "a", "persisted-marker"),
        )
    assertTrue(
        database.replicas.replace(
            scope,
            "restart",
            null,
            ReplicaSnapshot("published", "persisted", documents),
        )
    )
    assertTrue(database.replicas.begin(scope, "restart", "incomplete", "hidden", "published"))
    assertTrue(
        database.replicas.commit(
            ReplicaHandle(scope, "restart", "incomplete"),
            0,
            "half",
            listOf(documents[0].copy(version = 1, payload = "hidden-draft")),
            false,
        )
    )
}

suspend fun verifyReplicaExactRestart(database: AppDatabase) {
    val old = ReplicaScope("exact-restart", "exact-restart-owner")
    val resumed = old.copy(sessionId = "exact-resumed")
    database.accounts.activate(resumed.sessionId, resumed.accountKey)
    val queries =
        listOf(ReplicaQuery(id = "draft", limit = 1), ReplicaQuery(id = "marker", limit = 1))
    assertNull(database.replicas.observeQueries(old, "restart", queries).first().checkpoint)
    val page = database.replicas.observeQueries(resumed, "restart", queries).first()
    assertEquals("published", page.checkpoint?.handle?.generation)
    assertEquals("persisted", page.checkpoint?.payload)
    assertEquals(
        listOf("persisted-draft", "persisted-marker"),
        page.pages.map { it.single().payload },
    )
}
