package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take

private val handoffScope = CommandScope("handoff-session", "handoff-account")
private const val handoffPartition = "fixture/setup"
private const val handoffEvidence = "sealed-local-link-to-next-not-a-remote-receipt"
private val handoffOriginal =
    CommandRecord(handoffPartition, "original", "device", "001", "sealed-original", "pending")
private val handoffExpected = handoffOriginal.copy(state = "unconfirmed", revision = 1)
private val handoffNext =
    handoffOriginal.copy(id = "next", sortKey = "002", request = "sealed-linked-next")

private suspend fun insertHandoffOriginal(db: AppDatabase) {
    db.accounts.activate(handoffScope.sessionId, handoffScope.accountKey)
    assertEquals(CommandInsertResult.Stored, db.commands.insert(handoffScope, handoffOriginal))
    assertTrue(
        db.commands.update(handoffScope, handoffPartition, handoffOriginal.id, 0, "unconfirmed")
    )
}

suspend fun verifyCommandHandoffPreservesBoth(db: AppDatabase) {
    insertHandoffOriginal(db)
    val store = db.commands
    assertTrue(store.archiveAndInsert(handoffScope, handoffExpected, handoffEvidence, handoffNext))
    assertNull(store.get(handoffScope, handoffPartition, handoffExpected.id))
    assertEquals(
        CommandArchive(handoffExpected, handoffEvidence),
        store.archived(handoffScope, handoffPartition, handoffExpected.id),
    )
    assertEquals(listOf(handoffNext), store.observeAll(handoffScope, handoffPartition).first())
    // A repeated raw transition returns false. The owning workflow must read the retained link.
    assertFalse(store.archiveAndInsert(handoffScope, handoffExpected, handoffEvidence, handoffNext))
    assertTrue(store.update(handoffScope, handoffPartition, handoffNext.id, 0, "next-unconfirmed"))
    assertFalse(store.archiveAndInsert(handoffScope, handoffExpected, handoffEvidence, handoffNext))
    assertEquals(
        handoffNext.copy(state = "next-unconfirmed", revision = 1),
        store.get(handoffScope, handoffPartition, handoffNext.id),
    )
    assertEquals(CommandInsertResult.AlreadyStored, store.insert(handoffScope, handoffOriginal))
}

/** Runs after closing the connection used by verifyCommandHandoffPreservesBoth. */
suspend fun verifyCommandHandoffAfterReopen(db: AppDatabase) {
    val renewed = handoffScope.copy(sessionId = "handoff-new-login")
    db.accounts.activate(renewed.sessionId, renewed.accountKey)
    val store = db.commands
    assertNull(store.archived(handoffScope, handoffPartition, handoffExpected.id))
    assertEquals(
        CommandArchive(handoffExpected, handoffEvidence),
        store.archived(renewed, handoffPartition, handoffExpected.id),
    )
    val next = handoffNext.copy(state = "next-unconfirmed", revision = 1)
    assertEquals(next, store.get(renewed, handoffPartition, handoffNext.id))
    assertTrue(store.archive(renewed, next, "next-terminal-evidence"))
    assertFalse(store.archiveAndInsert(renewed, handoffExpected, handoffEvidence, handoffNext))
    assertNull(store.get(renewed, handoffPartition, handoffNext.id))
    assertEquals(
        CommandArchive(next, "next-terminal-evidence"),
        store.archived(renewed, handoffPartition, handoffNext.id),
    )
}

suspend fun verifyCommandHandoffConflictsAndRollback(db: AppDatabase) {
    insertHandoffOriginal(db)
    val store = db.commands
    val changed =
        listOf(
            handoffExpected.copy(request = "changed"),
            handoffExpected.copy(state = "changed"),
            handoffExpected.copy(sortKey = "changed"),
            handoffExpected.copy(revision = 0),
        )
    for (expected in changed) {
        assertFalse(store.archiveAndInsert(handoffScope, expected, handoffEvidence, handoffNext))
        assertEquals(handoffExpected, store.get(handoffScope, handoffPartition, handoffExpected.id))
        assertNull(store.archived(handoffScope, handoffPartition, handoffExpected.id))
        assertNull(store.get(handoffScope, handoffPartition, handoffNext.id))
    }
    // A unique sort-key violation happens at the last write, after archive + original removal.
    val occupied = handoffOriginal.copy(id = "occupied", resourceId = "other", sortKey = "002")
    assertEquals(CommandInsertResult.Stored, store.insert(handoffScope, occupied))
    assertFails {
        store.archiveAndInsert(handoffScope, handoffExpected, handoffEvidence, handoffNext)
    }
    assertEquals(
        listOf(handoffExpected, occupied),
        store.observeAll(handoffScope, handoffPartition).first(),
    )
    assertNull(store.archived(handoffScope, handoffPartition, handoffExpected.id))
    assertNull(store.get(handoffScope, handoffPartition, handoffNext.id))
    assertTrue(store.remove(handoffScope, handoffPartition, occupied.id, 0))
    // A retained successor ID must never be recreated, even when its request matches.
    assertTrue(
        store.archiveAbsent(handoffScope, handoffNext.copy(resourceId = "other"), "old-next")
    )
    assertFalse(store.archiveAndInsert(handoffScope, handoffExpected, handoffEvidence, handoffNext))
    assertEquals(handoffExpected, store.get(handoffScope, handoffPartition, handoffExpected.id))
    assertNull(store.archived(handoffScope, handoffPartition, handoffExpected.id))
}

suspend fun verifyCommandHandoffOwnershipAndBounds(db: AppDatabase) {
    insertHandoffOriginal(db)
    val store = db.commands
    val malformed =
        listOf(
            handoffNext.copy(id = handoffExpected.id),
            handoffNext.copy(partition = "foreign"),
            handoffNext.copy(resourceId = "foreign"),
            handoffNext.copy(revision = 1),
            handoffNext.copy(request = "x".repeat(131_073)),
            handoffNext.copy(state = "é".repeat(16_385)),
        )
    for (next in malformed) {
        assertFailsWith<IllegalArgumentException> {
            store.archiveAndInsert(handoffScope, handoffExpected, handoffEvidence, next)
        }
    }
    assertFailsWith<IllegalArgumentException> {
        store.archiveAndInsert(handoffScope, handoffExpected, "x".repeat(65_537), handoffNext)
    }
    val nextLogin = handoffScope.copy(sessionId = "renewed")
    db.accounts.activate(nextLogin.sessionId, nextLogin.accountKey)
    assertFalse(store.archiveAndInsert(handoffScope, handoffExpected, handoffEvidence, handoffNext))
    val other = CommandScope("other-session", "other-account")
    db.accounts.activate(other.sessionId, other.accountKey)
    assertFalse(store.archiveAndInsert(other, handoffExpected, handoffEvidence, handoffNext))
    assertNull(store.archived(other, handoffPartition, handoffExpected.id))
    db.accounts.activate(nextLogin.sessionId, nextLogin.accountKey)
    assertTrue(store.archiveAndInsert(nextLogin, handoffExpected, handoffEvidence, handoffNext))
}

suspend fun verifyCommandHandoffAtCapacity(db: AppDatabase) {
    insertHandoffOriginal(db)
    val store = db.commands
    val others =
        (1..127).map { index ->
            handoffOriginal.copy(
                id = "other-$index",
                resourceId = "other-$index",
                sortKey = "other-$index",
            )
        }
    assertEquals(CommandInsertResult.Stored, store.insertBatch(handoffScope, others))
    for (index in 2..4) {
        val scope = CommandScope("capacity-session-$index", "capacity-account-$index")
        db.accounts.activate(scope.sessionId, scope.accountKey)
        assertEquals(CommandInsertResult.Stored, store.insertBatch(scope, others + handoffOriginal))
    }
    db.accounts.activate(handoffScope.sessionId, handoffScope.accountKey)
    assertTrue(store.archiveAndInsert(handoffScope, handoffExpected, handoffEvidence, handoffNext))
    assertEquals(128, store.observeAll(handoffScope, handoffPartition).first().size)
    assertEquals(
        CommandInsertResult.CapacityExceeded,
        store.insert(
            handoffScope,
            handoffOriginal.copy(id = "excess", resourceId = "excess", sortKey = "excess"),
        ),
    )
    assertEquals(
        CommandArchive(handoffExpected, handoffEvidence),
        store.archived(handoffScope, handoffPartition, handoffExpected.id),
    )
}

suspend fun verifyCommandHandoffRaces(db: AppDatabase) = coroutineScope {
    insertHandoffOriginal(db)
    val store = db.commands
    val candidates = listOf(handoffNext, handoffNext.copy(id = "rival", request = "sealed-rival"))
    val race =
        candidates
            .map { next ->
                async {
                    store.archiveAndInsert(handoffScope, handoffExpected, "link-${next.id}", next)
                }
            }
            .awaitAll()
    assertEquals(1, race.count { it })
    val winner = candidates[race.indexOf(true)]
    assertEquals(listOf(winner), store.observeAll(handoffScope, handoffPartition).first())
    assertEquals(
        CommandArchive(handoffExpected, "link-${winner.id}"),
        store.archived(handoffScope, handoffPartition, handoffExpected.id),
    )
    val later = winner.copy(id = "third", request = "sealed-third")
    val updateRace =
        listOf(
                async { store.update(handoffScope, handoffPartition, winner.id, 0, "received") },
                async { store.archiveAndInsert(handoffScope, winner, "link-third", later) },
            )
            .awaitAll()
    assertEquals(1, updateRace.count { it })
    if (updateRace[0]) {
        assertEquals(
            winner.copy(state = "received", revision = 1),
            store.get(handoffScope, handoffPartition, winner.id),
        )
        assertNull(store.archived(handoffScope, handoffPartition, winner.id))
        assertNull(store.get(handoffScope, handoffPartition, later.id))
    } else {
        assertEquals(
            CommandArchive(winner, "link-third"),
            store.archived(handoffScope, handoffPartition, winner.id),
        )
        assertEquals(later, store.get(handoffScope, handoffPartition, later.id))
    }
}

suspend fun verifyCommandHandoffCancellation(db: AppDatabase) = coroutineScope {
    insertHandoffOriginal(db)
    val store = db.commands
    val job =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.archiveAndInsert(handoffScope, handoffExpected, handoffEvidence, handoffNext)
        }
    yield()
    job.cancelAndJoin()
    val original = store.get(handoffScope, handoffPartition, handoffExpected.id)
    val archive = store.archived(handoffScope, handoffPartition, handoffExpected.id)
    val next = store.get(handoffScope, handoffPartition, handoffNext.id)
    if (original != null) {
        assertEquals(handoffExpected, original)
        assertNull(archive)
        assertNull(next)
        assertTrue(
            store.archiveAndInsert(handoffScope, handoffExpected, handoffEvidence, handoffNext)
        )
    } else {
        assertEquals(CommandArchive(handoffExpected, handoffEvidence), archive)
        assertEquals(handoffNext, next)
        assertFalse(
            store.archiveAndInsert(handoffScope, handoffExpected, handoffEvidence, handoffNext)
        )
    }
}

suspend fun verifyCommandHandoffSessionRace(db: AppDatabase) = coroutineScope {
    insertHandoffOriginal(db)
    val store = db.commands
    val replacementLogin = handoffScope.copy(sessionId = "concurrent-replacement-login")
    val transition = async {
        store.archiveAndInsert(handoffScope, handoffExpected, handoffEvidence, handoffNext)
    }
    val switch = async {
        db.accounts.activate(replacementLogin.sessionId, replacementLogin.accountKey)
    }
    val changed = transition.await()
    switch.await()
    assertNull(store.get(handoffScope, handoffPartition, handoffNext.id))
    assertNull(store.archived(handoffScope, handoffPartition, handoffExpected.id))
    if (changed) {
        assertEquals(
            CommandArchive(handoffExpected, handoffEvidence),
            store.archived(replacementLogin, handoffPartition, handoffExpected.id),
        )
        assertEquals(handoffNext, store.get(replacementLogin, handoffPartition, handoffNext.id))
        assertNull(store.get(replacementLogin, handoffPartition, handoffExpected.id))
    } else {
        assertEquals(
            handoffExpected,
            store.get(replacementLogin, handoffPartition, handoffExpected.id),
        )
        assertNull(store.archived(replacementLogin, handoffPartition, handoffExpected.id))
        assertNull(store.get(replacementLogin, handoffPartition, handoffNext.id))
    }
}

suspend fun verifyCommandHandoffObservation(db: AppDatabase) = coroutineScope {
    insertHandoffOriginal(db)
    val observed = mutableListOf<List<CommandRecord>>()
    val initial = CompletableDeferred<Unit>()
    val collector =
        launch(start = CoroutineStart.UNDISPATCHED) {
            db.commands.observeAll(handoffScope, handoffPartition).take(2).collect {
                observed += it
                initial.complete(Unit)
            }
        }
    try {
        initial.await()
        assertTrue(
            db.commands.archiveAndInsert(
                handoffScope,
                handoffExpected,
                handoffEvidence,
                handoffNext,
            )
        )
        collector.join()
        assertEquals(listOf(listOf(handoffExpected), listOf(handoffNext)), observed)
    } finally {
        collector.cancelAndJoin()
    }
}
