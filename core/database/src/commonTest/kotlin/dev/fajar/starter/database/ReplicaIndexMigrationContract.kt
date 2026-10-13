package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.flow.first

suspend fun verifyReplicaIndexMigration(database: AppDatabase) {
    val scope = ReplicaScope("indexed-session", "indexed-account")
    val partition = "indexed/records"
    val store = database.replicas
    val queries = listOf(ReplicaQuery(), ReplicaQuery("tag"))
    val retained = store.observeQueries(scope, partition, queries).first()
    assertEquals("active-cursor", retained.checkpoint?.payload)
    assertEquals(
        listOf(ReplicaDocument("record", 2, "01", "original-ciphertext")),
        retained.pages[0],
    )
    assertTrue(retained.pages[1].isEmpty())
    val current = requireNotNull(store.checkpoint(scope, partition))
    assertEquals("staging-cursor", current.payload)
    assertFalse(current.published)
    assertTrue(
        store.commit(
            current.handle,
            3,
            "ready",
            listOf(
                ReplicaDocument("record", 3, "01", "staged-ciphertext", setOf("tag")),
                ReplicaDocument("deleted", 4, "02", "must-not-resurrect", setOf("tag")),
            ),
            true,
        )
    )
    val replacement = store.observeQueries(scope, partition, queries).first()
    assertEquals(1, replacement.pages[0].size)
    assertEquals(replacement.pages[0], replacement.pages[1])
    assertEquals("staged-ciphertext", replacement.pages[1].single().payload)
    database.accounts.activate("indexed-new-login", scope.accountKey)
    assertEquals(
        replacement.pages,
        store
            .observeQueries(scope.copy(sessionId = "indexed-new-login"), partition, queries)
            .first()
            .pages,
    )
    assertNull(store.observeQueries(scope, partition, queries).first().checkpoint)
}
