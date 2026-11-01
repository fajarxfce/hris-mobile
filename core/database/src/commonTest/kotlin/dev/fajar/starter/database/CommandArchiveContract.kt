package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private const val archivePartition = "company-a/completions"

private fun archivedCommand(id: String = "001") =
    CommandRecord(
        archivePartition,
        id,
        "resource-" + id,
        id,
        "sealed-request-" + id,
        "sealed-pending",
    )

private val archiveScope = CommandScope("completion-session", "completion-account")

suspend fun verifyCommandArchiveIdentity(database: AppDatabase) {
    val scope = archiveScope
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val store = database.commands
    val original = archivedCommand()
    assertEquals(CommandInsertResult.Stored, store.insert(scope, original))
    assertTrue(store.update(scope, archivePartition, original.id, 0, "sealed-result"))
    val completed = original.copy(revision = 1, state = "sealed-result")
    assertFalse(store.archive(scope, original, "sealed-evidence"))
    assertEquals(completed, store.get(scope, archivePartition, original.id))
    assertNull(store.archived(scope, archivePartition, original.id))
    assertTrue(store.archive(scope, completed, "sealed-evidence"))
    assertNull(store.get(scope, archivePartition, original.id))
    assertTrue(store.observeAll(scope, archivePartition).first().isEmpty())
    val retained = CommandArchive(completed, "sealed-evidence")
    assertEquals(retained, store.archived(scope, archivePartition, original.id))
    assertTrue(store.archive(scope, completed, "sealed-evidence"))
    assertFalse(store.archive(scope, original, "sealed-evidence"))
    assertFalse(store.archive(scope, completed, "replaced-evidence"))
    assertFalse(store.update(scope, archivePartition, original.id, 1, "replaced-state"))
    assertFalse(store.remove(scope, archivePartition, original.id, 1))
    assertEquals(CommandInsertResult.AlreadyStored, store.insert(scope, original))
    for (replacement in
        listOf(
            original.copy(request = "other"),
            original.copy(resourceId = "other"),
            original.copy(sortKey = "other"),
        )) {
        assertFails { store.insert(scope, replacement) }
    }
    assertNull(store.get(scope, archivePartition, original.id))
    assertEquals(retained, store.archived(scope, archivePartition, original.id))
    assertTrue(store.containsRecords())
    assertFalse(retained.toString().contains("sealed"))
}

suspend fun verifyCommandArchiveOwnership(database: AppDatabase) {
    val scope = archiveScope
    val store = database.commands
    val original = archivedCommand()
    database.accounts.activate(scope.sessionId, scope.accountKey)
    store.insert(scope, original)
    val other = scope.copy(sessionId = "other-session", accountKey = "other-account")
    database.accounts.activate(other.sessionId, other.accountKey)
    assertFalse(store.archive(scope, original, "evidence"))
    assertNull(store.archived(scope, archivePartition, original.id))
    store.insert(other, original.copy(request = "other-account"))
    assertTrue(store.archive(other, original.copy(request = "other-account"), "other-evidence"))
    assertNull(store.archived(other, "company-b/completions", original.id))
    database.accounts.activate(null)
    assertTrue(store.containsRecords())
    assertNull(store.archived(other, archivePartition, original.id))
    val resumed = scope.copy(sessionId = "new-login")
    database.accounts.activate(resumed.sessionId, resumed.accountKey)
    assertNull(store.archived(resumed, archivePartition, original.id))
    assertEquals(original, store.get(resumed, archivePartition, original.id))
    assertTrue(store.archive(resumed, original, "own-evidence"))
    assertEquals(
        CommandArchive(original, "own-evidence"),
        store.archived(resumed, archivePartition, original.id),
    )
    assertNull(store.archived(scope, archivePartition, original.id))
    database.accounts.activate(resumed.sessionId)
    assertNull(store.archived(resumed, archivePartition, original.id))
    assertFalse(store.archive(resumed, original, "own-evidence"))
}

suspend fun verifyCommandArchiveRaces(database: AppDatabase) = coroutineScope {
    val scope = archiveScope
    val store = database.commands
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val original = archivedCommand()
    store.insert(scope, original)
    val archived = async { store.archive(scope, original, "evidence") }
    val updated = async { store.update(scope, archivePartition, original.id, 0, "new-state") }
    assertEquals(1, listOf(archived.await(), updated.await()).count { it })
    if (archived.await()) {
        assertNull(store.get(scope, archivePartition, original.id))
        assertEquals(
            CommandArchive(original, "evidence"),
            store.archived(scope, archivePartition, original.id),
        )
    } else {
        assertEquals(
            original.copy(revision = 1, state = "new-state"),
            store.get(scope, archivePartition, original.id),
        )
        assertNull(store.archived(scope, archivePartition, original.id))
    }
    val next = archivedCommand("002")
    store.insert(scope, next)
    val choices = (1..2).map { async { store.archive(scope, next, "evidence-" + it) } }
    assertEquals(1, choices.count { it.await() })
    val retained = assertNotNull(store.archived(scope, archivePartition, next.id))
    assertEquals(next, retained.record)
    assertTrue(retained.evidence in setOf("evidence-1", "evidence-2"))
    val final = archivedCommand("003")
    store.insert(scope, final)
    val retirement = async { store.archive(scope, final, "final-evidence") }
    val repeated = async { store.insert(scope, final) }
    assertTrue(retirement.await())
    assertEquals(CommandInsertResult.AlreadyStored, repeated.await())
    assertNull(store.get(scope, archivePartition, final.id))
    assertEquals(
        CommandArchive(final, "final-evidence"),
        store.archived(scope, archivePartition, final.id),
    )
}

suspend fun verifyCommandArchiveCancellation(database: AppDatabase) = coroutineScope {
    val scope = archiveScope
    val store = database.commands
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val original = archivedCommand()
    store.insert(scope, original)
    val retirement =
        launch(start = CoroutineStart.UNDISPATCHED) { store.archive(scope, original, "evidence") }
    yield()
    retirement.cancelAndJoin()
    val retained = store.archived(scope, archivePartition, original.id)
    if (retained == null) assertEquals(original, store.get(scope, archivePartition, original.id))
    else {
        assertEquals(CommandArchive(original, "evidence"), retained)
        assertNull(store.get(scope, archivePartition, original.id))
    }
    assertTrue(store.archive(scope, original, "evidence"))
    val next = archivedCommand("002")
    store.insert(scope, next)
    val lost =
        object : CommandStore by store {
            override suspend fun archive(
                scope: CommandScope,
                expected: CommandRecord,
                evidence: String,
            ): Boolean {
                assertTrue(store.archive(scope, expected, evidence))
                error("Fixture lost committed response")
            }
        }
    assertFailsWith<IllegalStateException> { lost.archive(scope, next, "retained-proof") }
    assertNull(store.get(scope, archivePartition, next.id))
    assertEquals(
        CommandArchive(next, "retained-proof"),
        store.archived(scope, archivePartition, next.id),
    )
    assertEquals(CommandInsertResult.AlreadyStored, store.insert(scope, next))
}

suspend fun verifyCommandArchiveCapacity(database: AppDatabase) {
    val scope = archiveScope
    val store = database.commands
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val entries = (1..128).map { archivedCommand(it.toString().padStart(3, '0')) }
    for (entry in entries) assertEquals(CommandInsertResult.Stored, store.insert(scope, entry))
    assertEquals(CommandInsertResult.CapacityExceeded, store.insert(scope, archivedCommand("129")))
    val first = entries.first()
    assertTrue(store.archive(scope, first, "retained-completion"))
    val replacement =
        archivedCommand("129").copy(resourceId = first.resourceId, sortKey = first.sortKey)
    assertEquals(CommandInsertResult.Stored, store.insert(scope, replacement))
    assertEquals(128, store.observeAll(scope, archivePartition).first().size)
    assertEquals(CommandInsertResult.AlreadyStored, store.insert(scope, first))
    assertEquals(
        CommandArchive(first, "retained-completion"),
        store.archived(scope, archivePartition, first.id),
    )
    for (entry in entries.drop(1) + replacement) assertTrue(
        store.remove(scope, archivePartition, entry.id, 0)
    )
    assertTrue(store.observeAll(scope, archivePartition).first().isEmpty())
    assertTrue(store.containsRecords())
}

suspend fun verifyCommandArchiveBounds(database: AppDatabase) {
    val scope = archiveScope
    val store = database.commands
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val original = archivedCommand()
    store.insert(scope, original)
    for (evidence in listOf("", "x".repeat(65_537), "é".repeat(32_769))) {
        assertFailsWith<IllegalArgumentException> { store.archive(scope, original, evidence) }
    }
    for (revision in listOf(-1L, 9_007_199_254_740_992L)) {
        assertFailsWith<IllegalArgumentException> {
            store.archive(scope, original.copy(revision = revision), "evidence")
        }
    }
    assertEquals(original, store.get(scope, archivePartition, original.id))
    assertNull(store.archived(scope, archivePartition, original.id))
    assertFalse(store.archive(scope, original.copy(id = "unknown"), "evidence"))
    val evidence = "é".repeat(32_768)
    assertTrue(store.archive(scope, original, evidence))
    assertEquals(
        CommandArchive(original, evidence),
        store.archived(scope, archivePartition, original.id),
    )
}

suspend fun verifyCommandArchiveReplacementFence(database: AppDatabase) {
    val scope = archiveScope
    val store = database.commands
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val original = archivedCommand()
    store.insert(scope, original)
    assertTrue(store.remove(scope, archivePartition, original.id, 0))
    val replacement = original.copy(request = "replacement-request", state = "replacement-state")
    assertEquals(CommandInsertResult.Stored, store.insert(scope, replacement))
    assertFalse(store.archive(scope, original, "stale-proof"))
    assertEquals(replacement, store.get(scope, archivePartition, original.id))
    assertNull(store.archived(scope, archivePartition, original.id))
    for (changed in
        listOf(
            replacement.copy(state = "stale-state"),
            replacement.copy(resourceId = "changed-resource"),
            replacement.copy(sortKey = "changed-sort"),
        )) {
        assertFalse(store.archive(scope, changed, "stale-proof"))
    }
    assertTrue(store.archive(scope, replacement, "replacement-proof"))
    assertFalse(store.archive(scope, original, "replacement-proof"))
    assertFalse(
        store.archive(scope, replacement.copy(state = "changed-state"), "replacement-proof")
    )
    assertEquals(
        CommandArchive(replacement, "replacement-proof"),
        store.archived(scope, archivePartition, original.id),
    )
}
