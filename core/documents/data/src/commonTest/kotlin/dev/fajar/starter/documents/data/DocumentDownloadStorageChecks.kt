package dev.fajar.starter.documents.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.fileio.FILE_INPUT_BLOCK_BYTES
import dev.fajar.starter.fileio.Sha256Digest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

suspend fun verifyDocumentDownloadStorage(database: AppDatabase) = coroutineScope {
    val f = DocumentFileStorageFixture(database, DOCUMENT_DOWNLOAD_CHUNK_BYTES + 17)
    f.activate()
    val repository = f.repository()
    val draft = f.downloadDraft()
    assertEquals(
        AppResult.Success(DocumentFileReservation.CapacityExceeded),
        repository.reserveDownload(f.access, draft, 1, 1),
    )
    val reserved =
        assertIs<DocumentFileReservation.Stored>(
                assertIs<AppResult.Success<DocumentFileReservation>>(
                        repository.reserveDownload(
                            f.access,
                            draft,
                            DOCUMENT_LOCAL_CAPACITY,
                            DOCUMENT_LOCAL_FILES,
                        )
                    )
                    .value
            )
            .file
    assertEquals(
        AppResult.Success(DocumentFileReservation.Stored(reserved)),
        repository.reserveDownload(
            f.access,
            draft.copy(createdAt = draft.createdAt + 1.seconds),
            DOCUMENT_LOCAL_CAPACITY,
            DOCUMENT_LOCAL_FILES,
        ),
    )
    val source = requireNotNull(draft.download)
    assertEquals(
        AppResult.Success(DocumentFileReservation.Conflict),
        repository.reserveDownload(
            f.access,
            draft.copy(
                download = source.copy(evidence = source.evidence.copy(sha256 = "a".repeat(64)))
            ),
            DOCUMENT_LOCAL_CAPACITY,
            DOCUMENT_LOCAL_FILES,
        ),
    )
    val first = f.bytes.copyOfRange(0, DOCUMENT_DOWNLOAD_CHUNK_BYTES)
    val partial =
        requireNotNull(
            assertIs<AppResult.Success<LocalDocumentFile?>>(
                    repository.appendDownload(f.access, reserved, first)
                )
                .value
        )
    assertEquals(0, f.reads, "Downloads must not acquire an OS input")
    assertEquals(DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(), partial.storedBytes)
    assertNull(partial.sha256)
    assertEquals(16L, partial.revision)
    assertContentEquals(
        f.bytes.copyOfRange(0, DOCUMENT_DOWNLOAD_CHUNK_BYTES),
        first,
        "The caller owns its buffer",
    )
    first.fill(0)
    assertEquals(
        AppResult.Success(null),
        repository.appendDownload(f.access, reserved, ByteArray(DOCUMENT_DOWNLOAD_CHUNK_BYTES)),
    )
    val remaining = f.bytes.copyOfRange(DOCUMENT_DOWNLOAD_CHUNK_BYTES, f.bytes.size)
    val full =
        requireNotNull(
            assertIs<AppResult.Success<LocalDocumentFile?>>(
                    f.repository().appendDownload(f.access, partial, remaining)
                )
                .value
        )
    remaining.fill(0)
    val ready =
        assertIs<DocumentFileCompletion.Ready>(
                assertIs<AppResult.Success<DocumentFileCompletion>>(
                        repository.completeDownload(f.access, full)
                    )
                    .value
            )
            .file
    assertEquals(source.evidence.sha256, ready.sha256)
    assertEquals(draft, ready.draft)
    assertEquals(AppResult.Success(ready), f.repository().find(f.access, draft.id))
    assertEquals(
        AppResult.Success(DocumentFileCompletion.Ready(ready)),
        repository.completeDownload(f.access, ready),
    )
    val raw = requireNotNull(database.blobs.get(f.scope, f.partition, draft.id))
    assertFalse(raw.metadata.contains(source.evidence.revisionId))
    assertFalse(raw.metadata.contains(source.evidence.title))
    assertFalse(requireNotNull(raw.completion).contains(source.evidence.sha256))
    val copied =
        assertIs<AppResult.Success<DocumentFileChunk>>(repository.chunk(f.access, ready, 0)).value
    assertContentEquals(f.bytes.copyOfRange(0, DOCUMENT_DOWNLOAD_CHUNK_BYTES), copied.bytes)
    copied.bytes.fill(0)
}

suspend fun verifyDocumentDownloadResume(database: AppDatabase) = coroutineScope {
    val f = DocumentFileStorageFixture(database, DOCUMENT_DOWNLOAD_CHUNK_BYTES + 17)
    f.activate()
    val draft = f.downloadDraft()
    val reserved =
        assertIs<DocumentFileReservation.Stored>(
                assertIs<AppResult.Success<DocumentFileReservation>>(
                        f.repository()
                            .reserveDownload(
                                f.access,
                                draft,
                                DOCUMENT_LOCAL_CAPACITY,
                                DOCUMENT_LOCAL_FILES,
                            )
                    )
                    .value
            )
            .file
    val committed = CompletableDeferred<Unit>()
    val paused =
        f.repository(
            object : BlobStore by database.blobs {
                override suspend fun append(
                    scope: BlobScope,
                    partition: String,
                    id: String,
                    expectedRevision: Long,
                    chunk: BlobChunkRecord,
                ): Boolean {
                    assertTrue(database.blobs.append(scope, partition, id, expectedRevision, chunk))
                    committed.complete(Unit)
                    awaitCancellation()
                }
            }
        )
    val buffer = f.bytes.copyOfRange(0, DOCUMENT_DOWNLOAD_CHUNK_BYTES)
    val request = async { paused.appendDownload(f.access, reserved, buffer) }
    committed.await()
    request.cancelAndJoin()
    buffer.fill(0)
    val partial =
        requireNotNull(
            assertIs<AppResult.Success<LocalDocumentFile?>>(f.repository().find(f.access, draft.id))
                .value
        )
    assertEquals(FILE_INPUT_BLOCK_BYTES.toLong(), partial.storedBytes)
    assertNull(partial.sha256)
    val tail = f.bytes.copyOfRange(FILE_INPUT_BLOCK_BYTES, f.bytes.size)
    val full =
        requireNotNull(
            assertIs<AppResult.Success<LocalDocumentFile?>>(
                    f.repository().appendDownload(f.access, partial, tail)
                )
                .value
        )
    tail.fill(0)
    assertEquals(
        draft.download?.evidence?.sha256,
        assertIs<DocumentFileCompletion.Ready>(
                assertIs<AppResult.Success<DocumentFileCompletion>>(
                        f.repository().completeDownload(f.access, full)
                    )
                    .value
            )
            .file
            .sha256,
    )
    assertEquals(0, f.reads)
}

suspend fun verifyDocumentDownloadIntegrity(database: AppDatabase) = coroutineScope {
    val f = DocumentFileStorageFixture(database)
    f.activate()
    val repository = f.repository()
    val draft = f.downloadDraft()
    val reserved =
        assertIs<DocumentFileReservation.Stored>(
                assertIs<AppResult.Success<DocumentFileReservation>>(
                        repository.reserveDownload(
                            f.access,
                            draft,
                            DOCUMENT_LOCAL_CAPACITY,
                            DOCUMENT_LOCAL_FILES,
                        )
                    )
                    .value
            )
            .file
    val wrong = f.bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
    val full =
        requireNotNull(
            assertIs<AppResult.Success<LocalDocumentFile?>>(
                    repository.appendDownload(f.access, reserved, wrong)
                )
                .value
        )
    wrong.fill(0)
    assertEquals(
        AppResult.Success(DocumentFileCompletion.ChecksumMismatch),
        repository.completeDownload(f.access, full),
    )
    assertNull(database.blobs.get(f.scope, f.partition, draft.id)?.completion)
    assertIs<AppResult.Failed>(repository.chunk(f.access, full, 0))
    assertEquals(AppResult.Success(true), repository.remove(f.access, draft.id, full.revision))
    assertEquals(
        AppResult.Success(DocumentFileCompletion.Changed),
        repository.completeDownload(f.access, full),
    )
}

suspend fun verifyDocumentDownloadVerificationCancellation(database: AppDatabase) = coroutineScope {
    val f = DocumentFileStorageFixture(database)
    f.activate()
    val repository = f.repository()
    val draft = f.downloadDraft()
    val reserved =
        assertIs<DocumentFileReservation.Stored>(
                assertIs<AppResult.Success<DocumentFileReservation>>(
                        repository.reserveDownload(
                            f.access,
                            draft,
                            DOCUMENT_LOCAL_CAPACITY,
                            DOCUMENT_LOCAL_FILES,
                        )
                    )
                    .value
            )
            .file
    val buffer = f.bytes.copyOf()
    val full =
        requireNotNull(
            assertIs<AppResult.Success<LocalDocumentFile?>>(
                    repository.appendDownload(f.access, reserved, buffer)
                )
                .value
        )
    buffer.fill(0)
    val reading = CompletableDeferred<Unit>()
    val paused =
        f.repository(
            object : BlobStore by database.blobs {
                override suspend fun chunk(
                    scope: BlobScope,
                    partition: String,
                    id: String,
                    offset: Long,
                ): BlobChunkRecord? {
                    reading.complete(Unit)
                    awaitCancellation()
                }
            }
        )
    val request = async { paused.completeDownload(f.access, full) }
    reading.await()
    request.cancelAndJoin()
    assertNull(database.blobs.get(f.scope, f.partition, draft.id)?.completion)
    val aboutToComplete = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val stale =
        f.repository(
            object : BlobStore by database.blobs {
                override suspend fun complete(
                    scope: BlobScope,
                    partition: String,
                    id: String,
                    expectedRevision: Long,
                    completion: String,
                ): Boolean {
                    aboutToComplete.complete(Unit)
                    release.await()
                    return database.blobs.complete(
                        scope,
                        partition,
                        id,
                        expectedRevision,
                        completion,
                    )
                }
            }
        )
    val verification = async { stale.completeDownload(f.access, full) }
    aboutToComplete.await()
    assertEquals(AppResult.Success(true), repository.remove(f.access, draft.id, full.revision))
    release.complete(Unit)
    assertEquals(AppResult.Success(DocumentFileCompletion.Changed), verification.await())
    assertNull(database.blobs.get(f.scope, f.partition, draft.id))
}

private fun DocumentFileStorageFixture.downloadDraft(): DocumentFileDraft {
    val digest = Sha256Digest()
    digest.update(bytes)
    return draft.copy(
        download =
            DocumentDownloadSource(
                "44444444-4444-4444-4444-444444444444",
                DocumentClassification.PERSONAL,
                DocumentEvidence(
                    "55555555-5555-5555-5555-555555555555",
                    "66666666-6666-6666-6666-666666666666",
                    1,
                    "Personal document",
                    draft.declaration.name,
                    draft.declaration.mediaType,
                    draft.declaration.size,
                    digest.finishHex(),
                    draft.createdAt,
                ),
            )
    )
}
