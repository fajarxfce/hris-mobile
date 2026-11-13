package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal class ArchiveMutationFixture(val database: AppDatabase) {
    val scope = CommandScope("archive-session", "archive-account")
    val replicaScope = ReplicaScope(scope.sessionId, scope.accountKey)
    val command =
        CommandRecord("outgoing", "original", "resource", "001", "sealed-intent", "sealed-result")
    val evidence = "sealed-proof"
    val partitions = listOf("repair/progress", "inbox/projection")
    val document = ReplicaDocument("item", 1, "001", "before", setOf("visible"))
    lateinit var initial: List<ReplicaCheckpoint>

    suspend fun seed() {
        database.accounts.activate(scope.sessionId, scope.accountKey)
        assertTrue(
            database.replicas.mutate(
                replicaScope,
                partitions.map {
                    ReplicaMutation.Replace(
                        it,
                        null,
                        ReplicaSnapshot("initial", "before", listOf(document)),
                    )
                },
            )
        )
        initial = checkpoints()
        assertEquals(CommandInsertResult.Stored, database.commands.insert(scope, command))
    }

    suspend fun checkpoints(): List<ReplicaCheckpoint> =
        partitions.map { assertNotNull(database.replicas.checkpoint(replicaScope, it)) }

    fun mutations(label: String = "completed"): List<ReplicaMutation> =
        initial.map {
            ReplicaMutation.Commit(
                it.handle,
                it.revision,
                label,
                listOf(document.copy(version = 2, payload = label)),
                true,
            )
        }

    suspend fun unchanged() {
        assertEquals(command, database.commands.get(scope, command.partition, command.id))
        assertNull(database.commands.archived(scope, command.partition, command.id))
        assertEquals(initial, checkpoints())
        for (partition in partitions) assertEquals(
            document,
            database.replicas.document(replicaScope, partition, document.id),
        )
    }

    suspend fun completed(label: String = "completed") {
        assertNull(database.commands.get(scope, command.partition, command.id))
        assertEquals(
            CommandArchive(command, evidence),
            database.commands.archived(scope, command.partition, command.id),
        )
        assertEquals(listOf(label, label), checkpoints().map { it.payload })
        for (partition in partitions) assertEquals(
            label,
            database.replicas.document(replicaScope, partition, document.id)?.payload,
        )
    }
}

suspend fun verifyArchiveMutationCommit(database: AppDatabase) {
    val f = ArchiveMutationFixture(database)
    f.seed()
    val changes =
        listOf(
            ReplicaMutation.Replace(
                f.partitions[0],
                f.initial[0],
                ReplicaSnapshot(
                    "replacement",
                    "completed",
                    listOf(f.document.copy(payload = "completed")),
                ),
            ),
            f.mutations()[1],
        )
    assertTrue(database.commands.archiveAndMutate(f.scope, f.command, f.evidence, changes))
    f.completed()
    assertTrue(database.commands.observeAll(f.scope, f.command.partition).first().isEmpty())
}

suspend fun verifyArchiveMutationReplay(database: AppDatabase) {
    val f = ArchiveMutationFixture(database)
    f.seed()
    assertTrue(database.commands.archiveAndMutate(f.scope, f.command, f.evidence, f.mutations()))
    val later =
        f.checkpoints().map {
            ReplicaMutation.Replace(
                it.handle.partition,
                it,
                ReplicaSnapshot("later", "acknowledged", listOf(f.document)),
            )
        }
    assertTrue(database.replicas.mutate(f.replicaScope, later))
    val retained = f.checkpoints()
    assertTrue(database.commands.archiveAndMutate(f.scope, f.command, f.evidence, f.mutations()))
    assertFalse(database.commands.archiveAndMutate(f.scope, f.command, "different", f.mutations()))
    assertFalse(
        database.commands.archiveAndMutate(
            f.scope,
            f.command.copy(request = "different"),
            f.evidence,
            f.mutations(),
        )
    )
    assertEquals(retained, f.checkpoints())
    assertEquals(
        CommandArchive(f.command, f.evidence),
        database.commands.archived(f.scope, f.command.partition, f.command.id),
    )
}

suspend fun verifyArchiveMutationCommandConflicts(database: AppDatabase) {
    val f = ArchiveMutationFixture(database)
    f.seed()
    for (changed in
        listOf(
            f.command.copy(id = "absent"),
            f.command.copy(partition = "foreign"),
            f.command.copy(resourceId = "other"),
            f.command.copy(sortKey = "other"),
            f.command.copy(request = "other"),
            f.command.copy(state = "other"),
            f.command.copy(revision = 1),
        )) {
        assertFalse(database.commands.archiveAndMutate(f.scope, changed, f.evidence, f.mutations()))
        f.unchanged()
    }
}

suspend fun verifyArchiveMutationReplicaConflicts(database: AppDatabase) {
    val f = ArchiveMutationFixture(database)
    f.seed()
    for (index in 0..1) {
        val changed = f.mutations().toMutableList()
        changed[index] = (changed[index] as ReplicaMutation.Commit).copy(expectedRevision = 1)
        assertFalse(database.commands.archiveAndMutate(f.scope, f.command, f.evidence, changed))
        f.unchanged()
    }
    val reused =
        listOf(
            f.mutations()[0],
            ReplicaMutation.Replace(
                f.partitions[1],
                f.initial[1],
                ReplicaSnapshot("initial", "reused", emptyList()),
            ),
        )
    assertFalse(database.commands.archiveAndMutate(f.scope, f.command, f.evidence, reused))
    f.unchanged()
}

suspend fun verifyArchiveMutationConstraintRollback(database: AppDatabase) {
    val f = ArchiveMutationFixture(database)
    f.seed()
    val colliding =
        listOf(ReplicaDocument("a", 1, "same", "a"), ReplicaDocument("b", 1, "same", "b"))
    for (broken in
        listOf<ReplicaMutation>(
            ReplicaMutation.Replace(
                f.partitions[1],
                f.initial[1],
                ReplicaSnapshot("broken", "broken", colliding),
            ),
            (f.mutations()[1] as ReplicaMutation.Commit).copy(documents = colliding),
        )) {
        assertFails {
            database.commands.archiveAndMutate(
                f.scope,
                f.command,
                f.evidence,
                listOf(ReplicaMutation.Replace(f.partitions[0], f.initial[0], null), broken),
            )
        }
        f.unchanged()
    }
}

suspend fun verifyArchiveMutationOwnership(database: AppDatabase) {
    val f = ArchiveMutationFixture(database)
    f.seed()
    for ((session, account) in
        listOf(null to null, "other" to "other-account", "renewed" to f.scope.accountKey)) {
        database.accounts.activate(session, account)
        assertFalse(
            database.commands.archiveAndMutate(f.scope, f.command, f.evidence, f.mutations())
        )
        database.accounts.activate(f.scope.sessionId, f.scope.accountKey)
        f.unchanged()
    }
    val renewed = f.scope.copy(sessionId = "renewed")
    database.accounts.activate(renewed.sessionId, renewed.accountKey)
    val changes =
        f.mutations().map {
            (it as ReplicaMutation.Commit).copy(
                handle = it.handle.copy(scope = f.replicaScope.copy(sessionId = renewed.sessionId))
            )
        }
    assertTrue(database.commands.archiveAndMutate(renewed, f.command, f.evidence, changes))
    assertNull(database.commands.archived(f.scope, f.command.partition, f.command.id))
    assertEquals(
        CommandArchive(f.command, f.evidence),
        database.commands.archived(renewed, f.command.partition, f.command.id),
    )
}

suspend fun verifyArchiveMutationBounds(database: AppDatabase) {
    val f = ArchiveMutationFixture(database)
    f.seed()
    val first = f.mutations()[0] as ReplicaMutation.Commit
    for (changes in
        listOf(
            emptyList(),
            listOf(first, first),
            listOf(first, first, first),
            listOf(
                first.copy(
                    handle = first.handle.copy(scope = f.replicaScope.copy(accountKey = "other"))
                )
            ),
            listOf(first.copy(expectedRevision = -1)),
        )) {
        assertFailsWith<IllegalArgumentException> {
            database.commands.archiveAndMutate(f.scope, f.command, f.evidence, changes)
        }
        f.unchanged()
    }
    assertFailsWith<IllegalArgumentException> {
        database.commands.archiveAndMutate(f.scope, f.command, "", f.mutations())
    }
    f.unchanged()
    assertTrue(database.commands.archiveAndMutate(f.scope, f.command, f.evidence, f.mutations()))
    assertFailsWith<IllegalArgumentException> {
        database.commands.archiveAndMutate(f.scope, f.command, f.evidence, emptyList())
    }
    f.completed()
}

suspend fun verifyArchiveMutationCompetingWriters(database: AppDatabase) = coroutineScope {
    val f = ArchiveMutationFixture(database)
    f.seed()
    val archive = async {
        database.commands.archiveAndMutate(f.scope, f.command, f.evidence, f.mutations())
    }
    val update = async {
        database.commands.update(f.scope, f.command.partition, f.command.id, 0, "new-result")
    }
    assertEquals(1, listOf(archive.await(), update.await()).count { it })
    if (archive.await()) f.completed()
    else {
        assertEquals(f.initial, f.checkpoints())
        assertEquals(
            f.command.copy(revision = 1, state = "new-result"),
            database.commands.get(f.scope, f.command.partition, f.command.id),
        )
        assertNull(database.commands.archived(f.scope, f.command.partition, f.command.id))
    }
}

suspend fun verifyArchiveMutationCompetingProgress(database: AppDatabase) = coroutineScope {
    val f = ArchiveMutationFixture(database)
    f.seed()
    val archive = async {
        database.commands.archiveAndMutate(f.scope, f.command, f.evidence, f.mutations())
    }
    val progress = async { database.replicas.mutate(f.replicaScope, f.mutations("independent")) }
    assertEquals(1, listOf(archive.await(), progress.await()).count { it })
    if (archive.await()) f.completed()
    else {
        assertEquals(listOf("independent", "independent"), f.checkpoints().map { it.payload })
        assertEquals(f.command, database.commands.get(f.scope, f.command.partition, f.command.id))
        assertNull(database.commands.archived(f.scope, f.command.partition, f.command.id))
    }
}

suspend fun verifyArchiveMutationFrozenInput(database: AppDatabase) = coroutineScope {
    val f = ArchiveMutationFixture(database)
    f.seed()
    val tags = mutableSetOf("captured")
    val documents = mutableListOf(f.document.copy(version = 2, payload = "captured", tags = tags))
    val changes =
        f.mutations("captured")
            .map { (it as ReplicaMutation.Commit).copy(documents = documents) }
            .toMutableList()
    val write =
        async(start = CoroutineStart.UNDISPATCHED) {
            database.commands.archiveAndMutate(f.scope, f.command, f.evidence, changes)
        }
    tags.clear()
    documents.clear()
    changes.clear()
    assertTrue(write.await())
    f.completed("captured")
    for (partition in f.partitions) assertEquals(
        setOf("captured"),
        database.replicas.document(f.replicaScope, partition, f.document.id)?.tags,
    )
}

suspend fun verifyArchiveMutationCancellation(database: AppDatabase) = coroutineScope {
    val f = ArchiveMutationFixture(database)
    f.seed()
    launch {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> {
                database.commands.archiveAndMutate(f.scope, f.command, f.evidence, f.mutations())
            }
        }
        .join()
    f.unchanged()
    val write =
        launch(start = CoroutineStart.UNDISPATCHED) {
            database.commands.archiveAndMutate(f.scope, f.command, f.evidence, f.mutations())
        }
    write.cancelAndJoin()
    if (database.commands.archived(f.scope, f.command.partition, f.command.id) == null)
        f.unchanged()
    else f.completed()
}

suspend fun verifyArchiveMutationLostResponse(database: AppDatabase) {
    val f = ArchiveMutationFixture(database)
    f.seed()
    assertFailsWith<IllegalStateException> {
        check(database.commands.archiveAndMutate(f.scope, f.command, f.evidence, f.mutations()))
        error("Response lost after commit")
    }
    f.completed()
    val captured = f.checkpoints()
    assertTrue(database.commands.archiveAndMutate(f.scope, f.command, f.evidence, f.mutations()))
    assertEquals(captured, f.checkpoints())
}

suspend fun verifyArchiveMutationEmptyInventoryDemand(database: AppDatabase) = coroutineScope {
    val f = ArchiveMutationFixture(database)
    database.accounts.activate(f.scope.sessionId, f.scope.accountKey)
    val inventory = database.commands.observeAll(f.scope, f.command.partition)
    assertTrue(inventory.first().isEmpty())
    f.seed()
    val subscribed = CompletableDeferred<Unit>()
    val demand =
        async(start = CoroutineStart.UNDISPATCHED) {
            database.replicas.observe(f.replicaScope, f.partitions[0]).first {
                if (it.checkpoint == f.initial[0]) subscribed.complete(Unit)
                it.checkpoint?.payload == "completed"
            }
        }
    subscribed.await()
    assertTrue(database.commands.archiveAndMutate(f.scope, f.command, f.evidence, f.mutations()))
    assertEquals(
        "completed",
        withContext(Dispatchers.Default) { withTimeout(5_000) { demand.await() } }
            .checkpoint
            ?.payload,
    )
    assertTrue(inventory.first().isEmpty())
    // Even if the active snapshots conflate to the same empty list, the retained demand differs.
    val saved = database.replicas.observe(f.replicaScope, f.partitions[0]).first()
    assertEquals("completed", saved.checkpoint?.payload)
    assertEquals(
        CommandArchive(f.command, f.evidence),
        database.commands.observeRecord(f.scope, f.command.partition, f.command.id).first().archived,
    )
}

suspend fun prepareArchiveMutationRestart(database: AppDatabase) {
    val f = ArchiveMutationFixture(database)
    f.seed()
    assertTrue(database.commands.archiveAndMutate(f.scope, f.command, f.evidence, f.mutations()))
}

suspend fun verifyArchiveMutationRestart(database: AppDatabase) {
    val f = ArchiveMutationFixture(database)
    database.accounts.activate(f.scope.sessionId, f.scope.accountKey)
    f.completed()
    assertEquals(CommandInsertResult.AlreadyStored, database.commands.insert(f.scope, f.command))
}
