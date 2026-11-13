package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.flow.first

suspend fun verifyReplicaChangesMigration(db: AppDatabase) {
    val scope = ReplicaScope("migration-session", "migration-account")
    val partition = "migration/records"
    val original = ReplicaDocument("value", 900, "01", "original-ciphertext", setOf("visible"))
    val deleted = ReplicaDocument("deleted", 901, "02", null)
    val store = db.replicas
    val initial = assertIs<ReplicaChangePage.Items>(store.changedDocuments(scope, partition))
    assertEquals(12L, initial.checkpoint.revision)
    assertEquals("original-checkpoint", initial.checkpoint.payload)
    assertEquals(
        listOf(ReplicaChangedDocument(deleted, 0), ReplicaChangedDocument(original, 0)),
        initial.records,
    )
    assertNull(initial.next)
    assertEquals(
        listOf(original),
        store
            .observeQueries(scope, partition, listOf(ReplicaQuery("visible")))
            .first()
            .pages
            .single(),
    )
    val staged = assertNotNull(store.checkpoint(scope, partition))
    assertFalse(staged.published)
    assertEquals("staged-checkpoint", staged.payload)
    assertEquals(
        listOf(ReplicaDocument("value", 902, "01", "staged-ciphertext", setOf("hidden"))),
        store.workingDocuments(scope, partition, listOf("value")).documents,
    )
    assertTrue(store.discardStaging(scope, partition, staged))
    val changed = original.copy(payload = "next-ciphertext")
    assertTrue(
        store.commit(initial.checkpoint.handle, 12, "next-checkpoint", listOf(changed), true)
    )
    val next =
        assertIs<ReplicaChangePage.Items>(
            store.changedDocuments(
                scope,
                partition,
                ReplicaChangeCursor.After(initial.checkpoint.handle, 12),
            )
        )
    assertEquals(listOf(ReplicaChangedDocument(changed, 13)), next.records)
    assertEquals(900L, next.records.single().document.version)
    assertNull(store.document(scope, partition, deleted.id))
}
