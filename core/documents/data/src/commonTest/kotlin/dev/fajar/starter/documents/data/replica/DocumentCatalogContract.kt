package dev.fajar.starter.documents.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.replica.mappers.*
import dev.fajar.starter.documents.data.replica.repositories.EncryptedDocumentCatalogRepository
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.documents.domain.replica.policies.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

suspend fun checkCatalogPagesRestartAndOwnership(f: DocumentCatalogFixture) {
    val personal = (1..45).map { f.record(it) }
    val receipt = f.record(80, category = DocumentClassification.RECEIPT)
    val secondEmployment = f.record(81, employment = f.employments.last().id)
    f.publish(personal + receipt + secondEmployment)
    var cursor: String? = null
    val acquired = mutableListOf<DocumentEvidence>()
    repeat(3) {
        val page = f.snapshot(selection = f.selected.copy(after = cursor)).page
        assertTrue(page.items.size <= 20)
        acquired += page.items
        cursor = page.nextCursor
    }
    assertNull(cursor)
    assertEquals(personal.map { it.evidence }, acquired)
    assertEquals(
        listOf(receipt.evidence),
        f.snapshot(selection = f.selected.copy(classification = DocumentClassification.RECEIPT))
            .page
            .items,
    )
    assertEquals(
        listOf(secondEmployment.evidence),
        f.snapshot(selection = f.selected.copy(employmentId = f.employments.last().id)).page.items,
    )
    val next =
        f.access.copy(
            sessionId = "new-login",
            sessionExpiresAt = f.now - 73.hours,
            authorizationExpiresAt = f.now - 73.hours,
        )
    f.reopen(next)
    assertEquals(personal.take(20).map { it.evidence }, f.snapshot(next).page.items)
    assertNull(f.snapshot(next.copy(companyId = catalogFixtureId(999))).checkpoint)
    val other = next.copy(sessionId = "other", accountId = catalogFixtureId(998))
    f.activate(other)
    assertNull(f.snapshot(other).checkpoint)
    assertNull(f.snapshot(next).checkpoint)
    f.activate(next)
    assertEquals(personal.take(20).map { it.evidence }, f.snapshot(next).page.items)
}

suspend fun checkCatalogVersionsWithdrawalAndReplacement(f: DocumentCatalogFixture) {
    f.publish(listOf(f.record))
    val newer = f.record.copy(version = 2, evidence = f.record.evidence.copy(title = "Updated"))
    f.publish(listOf(newer))
    f.publish(listOf(f.record.copy(version = 1)))
    assertEquals("Updated", f.snapshot().page.items.single().title)
    var previous = f.checkpoint()!!
    assertEquals(
        AppResult.Success(true),
        f.repository.commit(
            previous,
            previous.copy(revision = previous.revision + 1),
            listOf(
                DocumentCatalogEntry(
                    SyncReference(SyncCollection.DOCUMENT_EVIDENCE, f.record.id, 3),
                    null,
                )
            ),
        ),
    )
    f.publish(listOf(newer))
    assertTrue(f.snapshot().page.items.isEmpty())
    f.publish(listOf(f.record.copy(version = 4)))
    val retained = f.snapshot()
    val replacement =
        initialDocumentCatalogCheckpoint(f.access, "replacement", f.now, f.employments)
    assertEquals(
        AppResult.Success(true),
        f.repository.begin(replacement, f.checkpoint()!!.generation),
    )
    f.publish(listOf(f.record(2)), publish = false)
    assertEquals(retained, f.snapshot())
    assertEquals(
        AppResult.Success(retained.checkpoint),
        f.repository.observePublishedCheckpoint(f.access).first(),
    )
    previous = f.checkpoint()!!
    assertEquals(
        AppResult.Success(true),
        f.repository.commit(
            previous,
            previous.copy(revision = previous.revision + 1, published = true, refreshedAt = f.now),
            emptyList(),
        ),
    )
    assertEquals(listOf(f.record(2).evidence), f.snapshot().page.items)
}

suspend fun checkCatalogEncryptionIndexesAndLostKey(f: DocumentCatalogFixture) {
    f.publish(listOf(f.record))
    val raw =
        f.db.replicas.document(
            documentCatalogReplicaScope(f.access),
            documentCatalogReplicaPartition(f.access),
            "document/${f.record.id}",
        )!!
    assertFalse(raw.payload!!.contains(f.record.evidence.title))
    assertFalse(raw.payload!!.contains(f.record.evidence.fileName))
    assertEquals(raw.id, raw.sortKey)
    assertEquals(1, raw.tags.size)
    assertFalse(raw.tags.single().contains(f.record.employmentId))
    assertFalse(raw.tags.single().contains("PERSONAL"))
    val before = f.checkpoint()!!
    val malformed =
        f.record(2).let { it.copy(evidence = it.evidence.copy(sha256 = "not-a-checksum")) }
    assertIs<AppResult.Failed>(
        f.repository.commit(
            before,
            before.copy(revision = before.revision + 1),
            listOf(
                DocumentCatalogEntry(
                    SyncReference(SyncCollection.DOCUMENT_EVIDENCE, malformed.id, 0),
                    malformed,
                )
            ),
        )
    )
    assertEquals(before, f.checkpoint())
    f.keys.write(null)
    assertIs<AppResult.Failed>(f.repository.observe(f.access, f.selected).first())
    assertIs<AppResult.Failed>(
        f.repository.begin(
            initialDocumentCatalogCheckpoint(f.access, "lost-key", f.now, f.employments),
            before.generation,
        )
    )
    assertNull(f.keys.read())
}

suspend fun checkCatalogCancellationDuringEncryption(f: DocumentCatalogFixture) = coroutineScope {
    f.publish(listOf(f.record))
    val before = f.checkpoint()!!
    val entered = CompletableDeferred<Unit>()
    val cipher =
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
    val repository = EncryptedDocumentCatalogRepository(f.db.replicas, cipher, f.keys, f.admissions)
    val job = launch {
        repository.commit(before, before.copy(revision = before.revision + 1), emptyList())
    }
    entered.await()
    job.cancelAndJoin()
    assertEquals(before, f.checkpoint())
    assertEquals(listOf(f.record.evidence), f.snapshot().page.items)
}

suspend fun checkCatalogReadsOnlyTheSelectedIndexedPage(f: DocumentCatalogFixture) {
    f.publish(
        (1..451).map {
            f.record(
                it,
                category =
                    if (it % 2 == 0) DocumentClassification.RECEIPT
                    else DocumentClassification.PERSONAL,
            )
        }
    )
    var decrypted = 0
    val cipher =
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
    val repository = EncryptedDocumentCatalogRepository(f.db.replicas, cipher, f.keys, f.admissions)
    val selected =
        f.selected.copy(
            classification = DocumentClassification.RECEIPT,
            after = catalogFixtureId(410),
        )
    val page =
        assertIs<AppResult.Success<DocumentCatalogSnapshot>>(
                repository.observe(f.access, selected).first()
            )
            .value
    assertEquals((412..450 step 2).map { f.record(it).evidence }, page.page.items)
    assertNull(page.page.nextCursor)
    assertTrue(decrypted <= 22, "Only the checkpoint and selected page may be decrypted")
}

suspend fun checkEmptyCatalogDoesNotCreateAKey(f: DocumentCatalogFixture) {
    assertNull(f.snapshot().checkpoint)
    assertNull(f.keys.read())
    f.publish(emptyList())
    assertNotNull(f.snapshot().checkpoint)
    assertTrue(f.snapshot().page.items.isEmpty())
    assertTrue(f.snapshot(selection = DocumentCatalogSelection()).page.items.isEmpty())
}
