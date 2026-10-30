package dev.fajar.starter.documents.data.metadata.working

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.metadata.DocumentMetadataStorageFixture
import dev.fajar.starter.documents.data.metadata.encoding.documentMetadataScope
import dev.fajar.starter.documents.data.metadata.mappers.toMetadataHead
import dev.fajar.starter.documents.data.metadata.working.encoding.*
import dev.fajar.starter.documents.data.metadata.working.repositories.EncryptedDocumentMetadataWorkingRepository
import dev.fajar.starter.documents.domain.metadata.working.entities.*
import dev.fajar.starter.documents.domain.metadata.working.policies.initialDocumentMetadataWorkingCopy
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

suspend fun checkMetadataWorkingRestartAndReviewedHeader(f: DocumentMetadataStorageFixture) {
    val initial = workingCopy(f)
    assertEquals(AppResult.Success(true), workingRepository(f).save(f.access, null, initial))
    val edited =
        initial.copy(
            revision = 1,
            input =
                initial.input.copy(
                    identityNumber = " PRIVATE edit ",
                    issuedOn = "2026-",
                    issuingCountry = "a",
                ),
            reason = "Reason\nstill being written",
        )
    assertEquals(AppResult.Success(true), workingRepository(f).save(f.access, initial, edited))
    f.reopen()
    assertEquals(edited, currentWorking(f))
    val renewed =
        f.access.copy(
            sessionId = "renewed",
            sessionExpiresAt = f.access.sessionExpiresAt + 365.days,
        )
    f.reopen(renewed)
    assertEquals(
        edited,
        assertIs<AppResult.Success<DocumentMetadataWorkingCopy?>>(
                workingRepository(f).observe(renewed, f.id).first()
            )
            .value,
    )
    assertIs<AppResult.Failed>(
        workingRepository(f)
            .save(
                renewed,
                edited,
                edited.copy(
                    revision = 2,
                    head = edited.head.copy(version = edited.head.version + 1),
                ),
            )
    )
    assertEquals(
        edited,
        assertIs<AppResult.Success<DocumentMetadataWorkingCopy?>>(
                workingRepository(f).current(renewed, f.id)
            )
            .value,
    )
}

suspend fun checkMetadataWorkingExactWritesAndExplicitDiscard(f: DocumentMetadataStorageFixture) {
    val initial = workingCopy(f)
    val repository = workingRepository(f)
    repository.save(f.access, null, initial)
    val edited = initial.copy(revision = 1, reason = "Correction")
    val forged = initial.copy(reason = "A different observed input")
    assertEquals(AppResult.Success(false), repository.save(f.access, forged, edited))
    assertEquals(AppResult.Success(false), repository.remove(f.access, forged))
    assertEquals(AppResult.Success(true), repository.save(f.access, initial, edited))
    assertEquals(AppResult.Success(false), repository.save(f.access, initial, edited))
    assertEquals(AppResult.Success(false), repository.remove(f.access, initial))
    assertEquals(AppResult.Success(true), repository.remove(f.access, edited))
    assertNull(currentWorking(f))
    val replacement =
        initial.copy(
            generation = "99999999-9999-9999-9999-999999999999",
            head = initial.head.copy(version = 4),
        )
    assertEquals(AppResult.Success(true), repository.save(f.access, null, replacement))
    assertEquals(AppResult.Success(false), repository.remove(f.access, edited))
    assertEquals(replacement, currentWorking(f))
}

suspend fun checkMetadataWorkingBoundedIndexedPages(f: DocumentMetadataStorageFixture) {
    val repository = workingRepository(f)
    repeat(45) { index ->
        assertEquals(
            AppResult.Success(true),
            repository.save(f.access, null, workingCopy(f, workingId(index))),
        )
    }
    var decrypts = 0
    val measured =
        object : ContentCipher by f.cipher {
            override suspend fun decrypt(
                key: ByteArray,
                associatedData: ByteArray,
                ciphertext: ByteArray,
            ): ByteArray {
                decrypts++
                return f.cipher.decrypt(key, associatedData, ciphertext)
            }
        }
    val page =
        assertIs<AppResult.Success<DocumentMetadataWorkingPage>>(
                workingRepository(f, content = measured).observePage(f.access, null).first()
            )
            .value
    assertEquals(20, page.items.size)
    assertEquals(20, decrypts)
    assertEquals(workingId(19), page.nextDocumentId)
    val second =
        assertIs<AppResult.Success<DocumentMetadataWorkingPage>>(
                repository.observePage(f.access, page.nextDocumentId).first()
            )
            .value
    val last =
        assertIs<AppResult.Success<DocumentMetadataWorkingPage>>(
                repository.observePage(f.access, second.nextDocumentId).first()
            )
            .value
    assertEquals(20, second.items.size)
    assertEquals(5, last.items.size)
    assertNull(last.nextDocumentId)
    assertEquals(
        (0..44).map(::workingId),
        (page.items + second.items + last.items).map { it.head.id },
    )
}

suspend fun checkMetadataWorkingKeyLossAndAccountIsolation(f: DocumentMetadataStorageFixture) {
    val initial = workingCopy(f)
    workingRepository(f).save(f.access, null, initial)
    val raw =
        assertNotNull(
            f.db.replicas.checkpoint(
                documentMetadataScope(f.access),
                metadataWorkingPartition(f.access, f.id),
            )
        )
    assertFalse(raw.payload.contains("FIXTURE-IDENTITY"))
    val key = assertNotNull(f.keys.read())
    f.keys.write(null)
    assertIs<AppResult.Failed>(workingRepository(f).current(f.access, f.id))
    assertIs<AppResult.Failed>(
        workingRepository(f).save(f.access, null, workingCopy(f, workingId(50)))
    )
    assertNull(f.keys.read())
    f.keys.write(key)
    val otherCompany = f.access.copy(companyId = workingId(60))
    assertNull(
        assertIs<AppResult.Success<DocumentMetadataWorkingCopy?>>(
                workingRepository(f).current(otherCompany, f.id)
            )
            .value
    )
    val other = f.access.copy(sessionId = "other", accountId = workingId(61))
    f.activate(other)
    assertNull(
        assertIs<AppResult.Success<DocumentMetadataWorkingCopy?>>(
                workingRepository(f).current(other, f.id)
            )
            .value
    )
    assertNull(currentWorking(f))
    f.activate()
    assertEquals(initial, currentWorking(f))
}

suspend fun checkMetadataWorkingTamperingAndEnvelopeBounds(f: DocumentMetadataStorageFixture) {
    workingRepository(f).save(f.access, null, workingCopy(f))
    val raw =
        assertNotNull(
            f.db.replicas.checkpoint(
                documentMetadataScope(f.access),
                metadataWorkingPartition(f.access, f.id),
            )
        )
    for (changed in
        listOf(
            raw.copy(revision = 1),
            raw.copy(handle = raw.handle.copy(generation = workingId(3))),
            raw.copy(payload = raw.payload.dropLast(5) + "AAAAA"),
            raw.copy(published = false),
        )) {
        val store =
            object : ReplicaStore by f.db.replicas {
                override suspend fun checkpoint(scope: ReplicaScope, partition: String) = changed
            }
        assertIs<AppResult.Failed>(workingRepository(f, store).current(f.access, f.id))
    }
    var decrypts = 0
    val store =
        object : ReplicaStore by f.db.replicas {
            override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                raw.copy(payload = "x".repeat(24_001))
        }
    val cipher =
        object : ContentCipher by f.cipher {
            override suspend fun decrypt(
                key: ByteArray,
                associatedData: ByteArray,
                ciphertext: ByteArray,
            ): ByteArray {
                decrypts++
                error("Oversized draft must not reach the cipher")
            }
        }
    assertIs<AppResult.Failed>(workingRepository(f, store, cipher).current(f.access, f.id))
    assertEquals(0, decrypts)
}

suspend fun checkMetadataWorkingCancellationAndLateAccount(f: DocumentMetadataStorageFixture) =
    coroutineScope {
        val initial = workingCopy(f)
        workingRepository(f).save(f.access, null, initial)
        val next = initial.copy(revision = 1, reason = "New input")
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
        val task = launch { workingRepository(f, content = pending).save(f.access, initial, next) }
        entered.await()
        task.cancelAndJoin()
        assertEquals(initial, currentWorking(f))
        val changed =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    f.activate(f.access.copy(sessionId = "replacement"))
                    return f.cipher.encrypt(key, associatedData, plaintext)
                }
            }
        assertEquals(
            AppResult.Success(false),
            workingRepository(f, content = changed).save(f.access, initial, next),
        )
        f.activate()
        assertEquals(initial, currentWorking(f))
    }

private fun workingRepository(
    f: DocumentMetadataStorageFixture,
    store: ReplicaStore = f.db.replicas,
    content: ContentCipher = f.cipher,
) = EncryptedDocumentMetadataWorkingRepository(store, content, f.keys)

private fun workingCopy(
    f: DocumentMetadataStorageFixture,
    id: String = f.id,
): DocumentMetadataWorkingCopy {
    val head = f.remote.head.copy(id = id).toMetadataHead(id)
    return assertIs<AppResult.Success<DocumentMetadataWorkingCopy>>(
            initialDocumentMetadataWorkingCopy(
                head,
                "77777777-7777-7777-7777-777777777777",
                head.createdAt,
            )
        )
        .value
}

private suspend fun currentWorking(f: DocumentMetadataStorageFixture) =
    assertIs<AppResult.Success<DocumentMetadataWorkingCopy?>>(
            workingRepository(f).current(f.access, f.id)
        )
        .value

private fun workingId(index: Int) = "30000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
