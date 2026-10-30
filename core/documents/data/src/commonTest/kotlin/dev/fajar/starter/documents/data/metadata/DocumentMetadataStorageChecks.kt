package dev.fajar.starter.documents.data.metadata

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.metadata.encoding.*
import dev.fajar.starter.documents.data.metadata.mappers.toMetadataRevision
import dev.fajar.starter.documents.data.metadata.repositories.EncryptedDocumentMetadataCacheRepository
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

suspend fun checkMetadataRestartPagingAndOwnership(f: DocumentMetadataStorageFixture) {
    f.append(44)
    val first = f.snapshot()
    assertEquals(45L, first.checkpoint?.head?.version)
    assertEquals(19L, first.checkpoint?.historyThrough)
    assertEquals((0L..19L).toList(), first.history.items.map { it.version })
    assertTrue(first.completePage)
    assertFalse(f.snapshot(19).completePage)
    val renewed =
        f.access.copy(
            sessionId = "renewed",
            sessionExpiresAt = f.access.sessionExpiresAt - 365.days,
        )
    f.reopen(renewed)
    assertEquals(first, f.snapshot(owner = renewed))
    f.append(44, renewed)
    assertEquals((20L..39L).toList(), f.snapshot(19, renewed).history.items.map { it.version })
    f.append(44, renewed)
    val last = f.snapshot(39, renewed)
    assertEquals((40L..44L).toList(), last.history.items.map { it.version })
    assertNull(last.history.nextCursor)
    assertTrue(last.completePage)
    assertNull(f.snapshot(owner = f.access).checkpoint)
    val foreign = renewed.copy(sessionId = "foreign", accountId = f.access.companyId)
    f.activate(foreign)
    assertNull(f.snapshot(owner = foreign).checkpoint)
    f.activate(renewed)
    assertNull(f.snapshot(owner = renewed.copy(companyId = f.access.accountId)).checkpoint)
    assertEquals(last, f.snapshot(39, renewed))
}

suspend fun checkMetadataExactWritesAndImmutableHistory(f: DocumentMetadataStorageFixture) {
    f.append(2)
    val before = f.snapshot()
    val old = requireNotNull(before.checkpoint)
    val unchanged = old.copy(revision = old.revision + 1)
    assertEquals(AppResult.Success(true), f.cache.commit(f.access, old, unchanged, emptyList()))
    assertEquals(AppResult.Success(false), f.cache.commit(f.access, old, unchanged, emptyList()))
    val changed = unchanged.copy(revision = unchanged.revision + 1)
    val altered = before.history.items.map { it.copy(reason = "REPLACED-PRIVATE-REASON") }
    assertIs<AppResult.Failed>(f.cache.commit(f.access, unchanged, changed, altered))
    assertEquals(before.history, f.snapshot().history)
    assertEquals(unchanged, f.checkpoint())
    assertEquals(
        AppResult.Success(true),
        f.cache.commit(f.access, unchanged, changed, before.history.items),
    )
    assertEquals(before.history, f.snapshot().history)
}

suspend fun checkMetadataNoHistoryGapOrRegressingBoundary(f: DocumentMetadataStorageFixture) {
    f.append(2)
    val previous = requireNotNull(f.checkpoint())
    val next =
        previous.copy(
            revision = previous.revision + 1,
            head = previous.head.copy(version = 5, metadataVersion = 4),
            historyThrough = 4,
        )
    assertIs<AppResult.Failed>(
        f.cache.commit(
            f.access,
            previous,
            next,
            listOf(f.remote.revision(4).toMetadataRevision(f.id)),
        )
    )
    assertEquals(previous, f.checkpoint())
    val empty =
        next.copy(head = next.head.copy(version = 4, metadataVersion = 3), historyThrough = 3)
    val nonIncreasing = f.remote.revision(3).copy(documentVersion = 3).toMetadataRevision(f.id)
    assertIs<AppResult.Failed>(f.cache.commit(f.access, previous, empty, listOf(nonIncreasing)))
    assertEquals(previous, f.checkpoint())
}

suspend fun checkMetadataEncryptionLostKeyAndSelectedPage(f: DocumentMetadataStorageFixture) {
    repeat(3) { f.append(44) }
    val scope = documentMetadataScope(f.access)
    val partition = documentMetadataPartition(f.access, f.id)
    val raw = f.db.replicas.observe(scope, partition, limit = 200).first()
    assertEquals(45, raw.documents.size)
    assertFalse(raw.toString().contains("FIXTURE"))
    var decrypted = 0
    val counted =
        object : ContentCipher by f.cipher {
            override suspend fun decrypt(
                key: ByteArray,
                associatedData: ByteArray,
                ciphertext: ByteArray,
            ): ByteArray {
                decrypted++
                return f.cipher.decrypt(key, associatedData, ciphertext)
            }
        }
    val reader = EncryptedDocumentMetadataCacheRepository(f.db.replicas, counted, f.keys)
    val result =
        assertIs<AppResult.Success<DocumentMetadataSnapshot>>(
                reader.observe(f.access, f.id, 19).first()
            )
            .value
    assertEquals(20, result.history.items.size)
    assertEquals(21, decrypted)
    val savedKey = requireNotNull(f.keys.read())
    val previous = requireNotNull(f.checkpoint())
    f.keys.write(null)
    assertIs<AppResult.Failed>(f.cache.observe(f.access, f.id, null).first())
    assertIs<AppResult.Failed>(
        f.cache.commit(
            f.access,
            previous,
            previous.copy(revision = previous.revision + 1),
            emptyList(),
        )
    )
    assertNull(f.keys.read())
    f.keys.write(savedKey)
    assertEquals(previous, f.checkpoint())
}

suspend fun checkMetadataCancelledEncryptionCannotCommit(f: DocumentMetadataStorageFixture) =
    coroutineScope {
        f.append(2)
        val previous = requireNotNull(f.checkpoint())
        val entered = CompletableDeferred<Unit>()
        val pending =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        val writer = EncryptedDocumentMetadataCacheRepository(f.db.replicas, pending, f.keys)
        val job = launch {
            writer.commit(
                f.access,
                previous,
                previous.copy(revision = previous.revision + 1),
                emptyList(),
            )
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(previous, f.checkpoint())
    }

suspend fun checkMetadataLateAccountCannotCommit(f: DocumentMetadataStorageFixture) =
    coroutineScope {
        f.append(2)
        val previous = requireNotNull(f.checkpoint())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val pending =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    entered.complete(Unit)
                    resume.await()
                    return f.cipher.encrypt(key, associatedData, plaintext)
                }
            }
        val writer = EncryptedDocumentMetadataCacheRepository(f.db.replicas, pending, f.keys)
        val job = async {
            writer.commit(
                f.access,
                previous,
                previous.copy(revision = previous.revision + 1),
                emptyList(),
            )
        }
        entered.await()
        f.activate(f.access.copy(sessionId = "foreign", accountId = f.access.companyId))
        resume.complete(Unit)
        assertEquals(AppResult.Success(false), job.await())
        f.activate()
        assertEquals(previous, f.checkpoint())
    }

suspend fun checkMetadataMaximumHistoryRemainsPaged(f: DocumentMetadataStorageFixture) {
    repeat(50) { f.append(999) }
    val last = f.snapshot(979)
    assertEquals(999L, last.checkpoint?.historyThrough)
    assertEquals((980L..999L).toList(), last.history.items.map { it.version })
    assertTrue(last.completePage)
    assertNull(last.history.nextCursor)
    f.reopen()
    assertEquals(last, f.snapshot(979))
}

suspend fun checkMetadataTamperedOwnershipAndRowsCannotDecode(f: DocumentMetadataStorageFixture) {
    f.append(2)
    val scope = documentMetadataScope(f.access)
    val partition = documentMetadataPartition(f.access, f.id)
    val raw = f.db.replicas.observe(scope, partition, limit = 20).first()
    val checkpoint = requireNotNull(raw.checkpoint)
    val corruptions =
        listOf(
            raw.copy(documents = raw.documents.drop(1)),
            raw.copy(
                documents =
                    listOf(raw.documents[0].copy(payload = raw.documents[1].payload)) +
                        raw.documents.drop(1)
            ),
            raw.copy(
                documents =
                    listOf(raw.documents[0].copy(sortKey = "revision/999")) + raw.documents.drop(1)
            ),
            raw.copy(checkpoint = checkpoint.copy(revision = checkpoint.revision + 1)),
            raw.copy(
                checkpoint =
                    checkpoint.copy(
                        handle = checkpoint.handle.copy(partition = "another-partition")
                    )
            ),
        )
    for (corrupt in corruptions) {
        val source =
            object : ReplicaStore by f.db.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ) = flowOf(corrupt)
            }
        val reader = EncryptedDocumentMetadataCacheRepository(source, f.cipher, f.keys)
        assertIs<AppResult.Failed>(reader.observe(f.access, f.id, null).first())
    }
    assertEquals(3, f.snapshot().history.items.size)
}
