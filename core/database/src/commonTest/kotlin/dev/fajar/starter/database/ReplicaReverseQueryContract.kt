package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.flow.first

/** Runs against real SQLite and IndexedDB; all reads retain a finite indexed page. */
suspend fun verifyReplicaReverseQueries(database: AppDatabase) {
    val scope = ReplicaScope("reverse-session", "reverse-account")
    val partition = "reverse-company/records"
    val store = database.replicas
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val records =
        (1..603).map { index ->
            val key = index.toString().padStart(4, '0')
            ReplicaDocument(
                "row-$key",
                1,
                key,
                "sealed-$key",
                setOf("all", if (index % 2 == 0) "even" else "odd"),
            )
        }
    assertTrue(store.begin(scope, partition, "initial", "staged", null))
    val handle = ReplicaHandle(scope, partition, "initial")
    records.chunked(200).forEachIndexed { index, batch ->
        assertTrue(store.commit(handle, index.toLong(), "page-$index", batch, index == 3))
    }
    for (tag in listOf(null, "all", "even", "missing")) {
        val expected = records.filter { tag == null || tag in it.tags }
        var before = "9999"
        val visited = mutableListOf<ReplicaDocument>()
        while (true) {
            val page =
                store
                    .observeQueries(
                        scope,
                        partition,
                        listOf(ReplicaQuery(tag, before = before, limit = 37)),
                    )
                    .first()
            assertEquals("page-3", page.checkpoint?.payload)
            val rows = page.pages.single()
            assertTrue(rows.size <= 37)
            assertTrue(rows.all { it.sortKey < before })
            if (rows.isEmpty()) break
            visited += rows
            before = rows.last().sortKey
        }
        assertEquals(expected.reversed(), visited)
    }
    // Adjacent forward/reverse ranges share a checkpoint, exclude their anchor and never cross
    // tags.
    val queries =
        listOf(
            ReplicaQuery("even", after = "0300", limit = 3),
            ReplicaQuery("even", before = "0300", limit = 3),
        )
    val paired = store.observeQueries(scope, partition, queries).first()
    assertEquals(listOf("0302", "0304", "0306"), paired.pages[0].map { it.sortKey })
    assertEquals(listOf("0298", "0296", "0294"), paired.pages[1].map { it.sortKey })
    assertTrue(
        store
            .observeQueries(scope, partition, listOf(ReplicaQuery(before = "0001")))
            .first()
            .pages
            .single()
            .isEmpty()
    )

    val removed = records[297].copy(version = 2, payload = null, tags = emptySet())
    val retagged = records[295].copy(version = 2, tags = setOf("odd"))
    assertTrue(store.commit(handle, 4, "changed", listOf(removed, retagged), true))
    assertEquals(
        listOf("0294", "0292", "0290"),
        store.observeQueries(scope, partition, queries).first().pages[1].map { it.sortKey },
    )
    assertEquals(
        listOf("0299", "0297", "0296"),
        store
            .observeQueries(scope, partition, listOf(ReplicaQuery(before = "0300", limit = 3)))
            .first()
            .pages
            .single()
            .map { it.sortKey },
    )
}
