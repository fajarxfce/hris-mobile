package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private const val partition = "company-a/documents"
private val scope = BlobScope("blob-session", "blob-account")

private fun blob(id: String = "001", size: Long = BLOB_BLOCK_BYTES + 27L) =
    BlobRecord(partition, id, size, "sealed-metadata-$id")

suspend fun verifyBlobAtomicity(database: AppDatabase) = coroutineScope {
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val store = database.blobs
    val original = blob()
    assertFalse(store.containsRecords())
    assertEquals(BlobInsertResult.Stored, store.insert(scope, original, MAX_BLOB_BYTES, 10))
    assertEquals(BlobInsertResult.AlreadyStored, store.insert(scope, original, MAX_BLOB_BYTES, 10))
    assertFalse(store.complete(scope, partition, original.id, 0, "early-proof"))
    val writes =
        (1..2).map { value ->
            async {
                store.append(
                    scope,
                    partition,
                    original.id,
                    0,
                    BlobChunkRecord(0, BLOB_BLOCK_BYTES, "sealed-$value"),
                )
            }
        }
    assertEquals(1, writes.count { it.await() })
    val first = assertNotNull(store.chunk(scope, partition, original.id, 0))
    assertTrue(first.payload in listOf("sealed-1", "sealed-2"))
    assertEquals(
        original.copy(storedBytes = BLOB_BLOCK_BYTES.toLong(), revision = 1),
        store.get(scope, partition, original.id),
    )
    assertFails { store.append(scope, partition, original.id, 1, first) }
    assertFails {
        store.append(
            scope,
            partition,
            original.id,
            1,
            BlobChunkRecord(BLOB_BLOCK_BYTES.toLong(), 26, "short"),
        )
    }
    assertNull(store.chunk(scope, partition, original.id, BLOB_BLOCK_BYTES.toLong()))
    val last = BlobChunkRecord(BLOB_BLOCK_BYTES.toLong(), 27, "sealed-final")
    assertTrue(store.append(scope, partition, original.id, 1, last))
    assertEquals(last, store.chunk(scope, partition, original.id, last.offset))
    val completion =
        (1..2).map { value ->
            async { store.complete(scope, partition, original.id, 2, "sealed-proof-$value") }
        }
    assertEquals(1, completion.count { it.await() })
    val sealed = assertNotNull(store.get(scope, partition, original.id))
    assertEquals(3, sealed.revision)
    assertEquals(original.size, sealed.storedBytes)
    assertTrue(sealed.completion in listOf("sealed-proof-1", "sealed-proof-2"))
    assertFalse(store.complete(scope, partition, original.id, 3, "replacement-proof"))
    assertFalse(store.append(scope, partition, original.id, 3, first))
    assertEquals(BlobInsertResult.AlreadyStored, store.insert(scope, original, MAX_BLOB_BYTES, 10))
    assertFails { store.insert(scope, original.copy(metadata = "replacement"), MAX_BLOB_BYTES, 10) }
    assertFails { store.insert(scope, original.copy(size = 1), MAX_BLOB_BYTES, 10) }
    assertEquals(sealed, store.get(scope, partition, original.id))
    assertFalse(store.remove(scope, partition, original.id, 2))
    assertTrue(store.remove(scope, partition, original.id, 3))
    assertNull(store.chunk(scope, partition, original.id, 0))
    assertNull(store.chunk(scope, partition, original.id, last.offset))
    assertFalse(store.containsRecords())
    assertTrue(store.observe(scope, partition).first().isEmpty())
}

suspend fun verifyBlobOwnership(database: AppDatabase) = coroutineScope {
    val store = database.blobs
    val original = blob(size = 3)
    val chunk = BlobChunkRecord(0, 3, "sealed-content")
    assertEquals(BlobInsertResult.ScopeChanged, store.insert(scope, original, MAX_BLOB_BYTES, 10))
    database.accounts.activate(scope.sessionId, scope.accountKey)
    assertEquals(BlobInsertResult.Stored, store.insert(scope, original, MAX_BLOB_BYTES, 10))
    assertTrue(store.append(scope, partition, original.id, 0, chunk))
    val secondCompany = original.copy(partition = "company-b/documents", metadata = "company-b")
    assertEquals(BlobInsertResult.Stored, store.insert(scope, secondCompany, MAX_BLOB_BYTES, 10))
    assertNull(store.chunk(scope, secondCompany.partition, original.id, 0))
    val hidden =
        async(start = CoroutineStart.UNDISPATCHED) {
            store.observe(scope, partition).first { it.isEmpty() }
        }
    database.accounts.activate(null)
    assertTrue(hidden.await().isEmpty())
    assertTrue(store.containsRecords())
    val other = BlobScope("other-session", "other-account")
    database.accounts.activate(other.sessionId, other.accountKey)
    assertNull(store.get(other, partition, original.id))
    assertNull(store.chunk(scope, partition, original.id, 0))
    assertNull(store.chunk(other.copy(accountKey = scope.accountKey), partition, original.id, 0))
    assertFalse(store.complete(scope, partition, original.id, 1, "late-proof"))
    assertFalse(store.updateState(scope, partition, original.id, 1, "late-state"))
    assertFalse(store.remove(scope, partition, original.id, 1))
    assertEquals(
        BlobInsertResult.Stored,
        store.insert(other, original.copy(metadata = "other-account"), MAX_BLOB_BYTES, 10),
    )
    val resumed = scope.copy(sessionId = "replacement-session")
    database.accounts.activate(resumed.sessionId, resumed.accountKey)
    assertEquals(chunk, store.chunk(resumed, partition, original.id, 0))
    assertEquals(secondCompany, store.get(resumed, secondCompany.partition, original.id))
    assertEquals(
        BlobInsertResult.ScopeChanged,
        store.insert(scope, blob("late"), MAX_BLOB_BYTES, 10),
    )
    assertFalse(store.append(scope, secondCompany.partition, original.id, 0, chunk))
    assertTrue(store.complete(resumed, partition, original.id, 1, "resumed-proof"))
    database.accounts.activate(resumed.sessionId)
    assertNull(store.get(resumed, partition, original.id))
    assertNull(store.chunk(resumed, partition, original.id, 0))
}

suspend fun verifyBlobBounds(database: AppDatabase) {
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val store = database.blobs
    val original = blob(size = 2)
    for (invalid in
        listOf(
            original.copy(size = 0),
            original.copy(size = MAX_BLOB_BYTES + 1),
            original.copy(storedBytes = 1),
            original.copy(state = "early-checkpoint"),
            original.copy(revision = 1),
            original.copy(completion = "early"),
            original.copy(metadata = "\u00e9".repeat(8193)),
        )) {
        assertFailsWith<IllegalArgumentException> {
            store.insert(scope, invalid, MAX_BLOB_BYTES, 10)
        }
    }
    assertFailsWith<IllegalArgumentException> {
        store.insert(scope, original, MAX_BLOB_ACCOUNT_BYTES + 1, 10)
    }
    assertFailsWith<IllegalArgumentException> { store.insert(scope, original, MAX_BLOB_BYTES, 21) }
    assertFalse(store.containsRecords())
    assertEquals(BlobInsertResult.Stored, store.insert(scope, original, MAX_BLOB_BYTES, 10))
    for (invalid in
        listOf(
            BlobChunkRecord(1, 2, "wrong-offset"),
            BlobChunkRecord(0, 0, "empty"),
            BlobChunkRecord(0, BLOB_BLOCK_BYTES + 1, "overlong"),
            BlobChunkRecord(0, 2, "x".repeat(131_073)),
        )) {
        assertFailsWith<IllegalArgumentException> {
            store.append(scope, partition, original.id, 0, invalid)
        }
    }
    assertFails { store.append(scope, partition, original.id, 0, BlobChunkRecord(0, 1, "short")) }
    assertFailsWith<IllegalArgumentException> {
        store.complete(scope, partition, original.id, 0, "\u00e9".repeat(8193))
    }
    assertFailsWith<IllegalArgumentException> {
        store.remove(scope, partition, original.id, 9_007_199_254_740_991L)
    }
    assertFailsWith<IllegalArgumentException> {
        store.observe(scope, partition, limit = 51).first()
    }
    assertFailsWith<IllegalArgumentException> {
        store.chunk(scope, partition, original.id, MAX_BLOB_BYTES)
    }
    assertEquals(original, store.get(scope, partition, original.id))
    assertNull(store.chunk(scope, partition, original.id, 0))
}

suspend fun verifyBlobQuotaAndPages(database: AppDatabase) = coroutineScope {
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val store = database.blobs
    val records = (1..20).map { blob(it.toString().padStart(3, '0'), 1) }
    for (entry in records) assertEquals(BlobInsertResult.Stored, store.insert(scope, entry, 20, 20))
    assertEquals(
        BlobInsertResult.CapacityExceeded,
        store.insert(scope, blob("overflow", 1), 21, 20),
    )
    val first = store.observe(scope, partition, limit = 7).first()
    val second = store.observe(scope, partition, after = first.last().id, limit = 7).first()
    val third = store.observe(scope, partition, after = second.last().id, limit = 7).first()
    assertEquals(records, first + second + third)
    assertTrue(store.remove(scope, partition, records.first().id, 0))
    val contenders =
        (1..2).map { value ->
            async {
                store.insert(
                    scope,
                    blob("contender-$value", 1).copy(partition = "another-company"),
                    20,
                    20,
                )
            }
        }
    assertEquals(1, contenders.count { it.await() == BlobInsertResult.Stored })
    assertEquals(1, store.observe(scope, "another-company").first().size)
}

suspend fun verifyBlobGlobalReservation(database: AppDatabase) {
    val store = database.blobs
    val maximum = blob(size = MAX_BLOB_BYTES)
    for (account in 1..2) {
        val owner = BlobScope("large-session-$account", "large-account-$account")
        database.accounts.activate(owner.sessionId, owner.accountKey)
        assertEquals(
            BlobInsertResult.Stored,
            store.insert(owner, maximum, MAX_BLOB_ACCOUNT_BYTES, 20),
        )
        assertEquals(
            BlobInsertResult.Stored,
            store.insert(owner, maximum.copy(id = "second"), MAX_BLOB_ACCOUNT_BYTES, 20),
        )
        assertEquals(
            BlobInsertResult.CapacityExceeded,
            store.insert(owner, blob("extra", 1), MAX_BLOB_ACCOUNT_BYTES, 20),
        )
    }
    database.accounts.activate(scope.sessionId, scope.accountKey)
    assertEquals(BlobInsertResult.Stored, store.insert(scope, maximum, MAX_BLOB_ACCOUNT_BYTES, 20))
    assertEquals(
        BlobInsertResult.CapacityExceeded,
        store.insert(scope, blob("overflow", 1), MAX_BLOB_ACCOUNT_BYTES, 20),
    )
    assertTrue(store.remove(scope, partition, maximum.id, 0))
    assertEquals(
        BlobInsertResult.Stored,
        store.insert(scope, blob("replacement", 1), MAX_BLOB_ACCOUNT_BYTES, 20),
    )
}

suspend fun verifyBlobGlobalItemLimit(database: AppDatabase) {
    for (index in 0 until 64) {
        val owner = BlobScope("session-${index / 20}", "account-${index / 20}")
        database.accounts.activate(owner.sessionId, owner.accountKey)
        assertEquals(
            BlobInsertResult.Stored,
            database.blobs.insert(owner, blob(index.toString(), 1), 100, 20),
        )
    }
    val last = BlobScope("session-3", "account-3")
    assertEquals(
        BlobInsertResult.CapacityExceeded,
        database.blobs.insert(last, blob("overflow", 1), 100, 20),
    )
}

suspend fun verifyBlobCancellation(database: AppDatabase) = coroutineScope {
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val store = database.blobs
    val original = blob(size = 3)
    val block = BlobChunkRecord(0, 3, "sealed-content")
    assertEquals(BlobInsertResult.Stored, store.insert(scope, original, MAX_BLOB_BYTES, 10))
    val writer =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.append(scope, partition, original.id, 0, block)
        }
    yield()
    writer.cancelAndJoin()
    val current = assertNotNull(store.get(scope, partition, original.id))
    if (current.revision == 0L) {
        assertEquals(original, current)
        assertNull(store.chunk(scope, partition, original.id, 0))
        assertTrue(store.append(scope, partition, original.id, 0, block))
    } else {
        assertEquals(original.copy(storedBytes = 3, revision = 1), current)
        assertEquals(block, store.chunk(scope, partition, original.id, 0))
    }
    val observed =
        async(start = CoroutineStart.UNDISPATCHED) {
            store.observe(scope, partition).first { it.singleOrNull()?.completion != null }
        }
    assertTrue(store.complete(scope, partition, original.id, 1, "sealed-proof"))
    assertEquals("sealed-proof", observed.await().single().completion)
    val deletion =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.remove(scope, partition, original.id, 2)
        }
    yield()
    deletion.cancelAndJoin()
    val remaining = store.get(scope, partition, original.id)
    if (remaining == null) assertNull(store.chunk(scope, partition, original.id, 0))
    else {
        assertEquals(2, remaining.revision)
        assertEquals(block, store.chunk(scope, partition, original.id, 0))
    }
}

suspend fun verifyBlobCheckpoints(database: AppDatabase) = coroutineScope {
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val store = database.blobs
    val original = blob(size = 3)
    val block = BlobChunkRecord(0, 3, "sealed-content")
    assertEquals(BlobInsertResult.Stored, store.insert(scope, original, 10, 1))
    assertFalse(store.updateState(scope, partition, original.id, 0, "early"))
    assertTrue(store.append(scope, partition, original.id, 0, block))
    assertTrue(store.complete(scope, partition, original.id, 1, "sealed-proof"))
    val observed =
        async(start = CoroutineStart.UNDISPATCHED) {
            store.observe(scope, partition).first { it.singleOrNull()?.state != null }
        }
    val writers =
        (1..2).map { value ->
            async {
                store.updateState(scope, partition, original.id, 2, "sealed-checkpoint-$value")
            }
        }
    assertEquals(1, writers.count { it.await() })
    val committed = observed.await().single()
    assertEquals(3, committed.revision)
    assertEquals("sealed-proof", committed.completion)
    assertEquals(original.metadata, committed.metadata)
    assertEquals(original.size, committed.storedBytes)
    assertEquals(block, store.chunk(scope, partition, original.id, 0))
    assertFalse(store.updateState(scope, partition, original.id, 2, "late-checkpoint"))
    assertFalse(store.remove(scope, partition, original.id, 2))
    assertFailsWith<IllegalArgumentException> {
        store.updateState(scope, partition, original.id, 3, "x".repeat(16_385))
    }
    val pending =
        launch(start = CoroutineStart.UNDISPATCHED) {
            store.updateState(scope, partition, original.id, 3, "sealed-after-interruption")
        }
    yield()
    pending.cancelAndJoin()
    val resumed = assertNotNull(store.get(scope, partition, original.id))
    assertTrue(
        resumed == committed ||
            resumed == committed.copy(revision = 4, state = "sealed-after-interruption")
    )
    assertTrue(store.remove(scope, partition, original.id, resumed.revision))
    assertFalse(store.updateState(scope, partition, original.id, resumed.revision, "after-removal"))
    assertNull(store.chunk(scope, partition, original.id, 0))
}

suspend fun verifyBlobCapacityAcrossConnections(first: AppDatabase, second: AppDatabase) =
    coroutineScope {
        first.accounts.activate(scope.sessionId, scope.accountKey)
        val writers =
            listOf(first, second).mapIndexed { index, database ->
                async { database.blobs.insert(scope, blob("concurrent-$index", 3), 3, 1) }
            }
        assertEquals(1, writers.count { it.await() == BlobInsertResult.Stored })
        assertEquals(
            first.blobs.observe(scope, partition).first(),
            second.blobs.observe(scope, partition).first(),
        )
        assertEquals(1, first.blobs.observe(scope, partition).first().size)
    }
