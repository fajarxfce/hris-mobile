package dev.fajar.starter.documents.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.encoding.documentFilePartition
import dev.fajar.starter.documents.data.repositories.EncryptedDocumentFileRepository
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.fileio.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

suspend fun verifyEncryptedDocumentImport(database: AppDatabase) = coroutineScope {
    val f = DocumentFileStorageFixture(database)
    f.activate()
    val repository = f.repository()
    val first = async {
        repository.import(f.access, f.draft, DOCUMENT_LOCAL_CAPACITY, DOCUMENT_LOCAL_FILES)
    }
    val duplicate = async {
        repository.import(f.access, f.draft, DOCUMENT_LOCAL_CAPACITY, DOCUMENT_LOCAL_FILES)
    }
    val file =
        assertIs<DocumentFileImport.Stored>(
                assertIs<AppResult.Success<DocumentFileImport>>(first.await()).value
            )
            .file
    assertEquals(AppResult.Success(DocumentFileImport.Stored(file)), duplicate.await())
    assertEquals(1, f.reads)
    assertEquals(1, f.readClosures)
    assertEquals(f.draft.declaration.size, file.storedBytes)
    assertEquals("1ec3437cee3cccf3647e130524ade52848571960a3340ae33573beef17d330b3", file.sha256)
    assertEquals(4L, file.revision)
    assertEquals(AppResult.Success(file), repository.find(f.access, f.draft.id))
    assertEquals(AppResult.Success(listOf(file)), repository.observe(f.access).first())
    assertEquals(AppResult.Success(false), repository.discardIncomplete(f.access, f.draft.id))
    val raw = requireNotNull(database.blobs.get(f.scope, f.partition, f.draft.id))
    assertFalse(raw.metadata.contains(f.draft.declaration.name))
    assertFalse(requireNotNull(raw.completion).contains(requireNotNull(file.sha256)))
    val key = Base64.decode(requireNotNull(f.keys.read()))
    try {
        for (offset in listOf(0L, 65536L, 131072L)) {
            val block =
                requireNotNull(database.blobs.chunk(f.scope, f.partition, f.draft.id, offset))
            val aad = f.associatedData(offset)
            val bytes = f.cipher.decrypt(key, aad, Base64.decode(block.payload))
            val length = minOf(65536, f.bytes.size - offset.toInt())
            assertEquals(length, block.length)
            assertContentEquals(f.bytes.copyOfRange(offset.toInt(), offset.toInt() + length), bytes)
            bytes.fill(0)
            assertFails {
                f.cipher.decrypt(
                    key,
                    f.associatedData(offset + 65536),
                    Base64.decode(block.payload),
                )
            }
        }
    } finally {
        key.fill(0)
    }
}

suspend fun verifyDocumentFileOwnership(database: AppDatabase) {
    val f = DocumentFileStorageFixture(database)
    f.activate()
    val original =
        assertIs<AppResult.Success<DocumentFileImport>>(
                f.repository()
                    .import(f.access, f.draft, DOCUMENT_LOCAL_CAPACITY, DOCUMENT_LOCAL_FILES)
            )
            .value
    val repository = f.repository()
    val other =
        f.access.copy(
            sessionId = "other-session",
            accountId = "66666666-6666-6666-6666-666666666666",
        )
    database.accounts.activate(other.sessionId, accountStorageKey(other.audience, other.accountId))
    assertEquals(AppResult.Success(null), repository.find(other, f.draft.id))
    assertEquals(AppResult.Success(null), repository.find(f.access, f.draft.id))
    val restored = f.access.copy(sessionId = "restored-session")
    database.accounts.activate(restored.sessionId, f.scope.accountKey)
    assertEquals(
        AppResult.Success(assertIs<DocumentFileImport.Stored>(original).file),
        repository.find(restored, f.draft.id),
    )
    val otherCompany = restored.copy(companyId = "77777777-7777-7777-7777-777777777777")
    val row =
        requireNotNull(
            database.blobs.get(
                f.scope.copy(sessionId = restored.sessionId),
                f.partition,
                f.draft.id,
            )
        )
    val forged = BlobRecord(documentFilePartition(otherCompany), row.id, row.size, row.metadata)
    assertEquals(
        BlobInsertResult.Stored,
        database.blobs.insert(
            f.scope.copy(sessionId = restored.sessionId),
            forged,
            DOCUMENT_LOCAL_CAPACITY,
            DOCUMENT_LOCAL_FILES,
        ),
    )
    assertEquals(
        FailureKind.Storage,
        assertIs<AppResult.Failed>(repository.find(otherCompany, f.draft.id)).failure.kind,
    )
}

suspend fun verifyDocumentImportCancellation(database: AppDatabase) = coroutineScope {
    val f = DocumentFileStorageFixture(database)
    f.activate()
    val appended = CompletableDeferred<Unit>()
    val held =
        object : BlobStore by database.blobs {
            override suspend fun append(
                scope: BlobScope,
                partition: String,
                id: String,
                expectedRevision: Long,
                chunk: BlobChunkRecord,
            ): Boolean {
                assertTrue(database.blobs.append(scope, partition, id, expectedRevision, chunk))
                appended.complete(Unit)
                awaitCancellation()
            }
        }
    val repository = f.repository(held)
    val operation = async {
        repository.import(f.access, f.draft, DOCUMENT_LOCAL_CAPACITY, DOCUMENT_LOCAL_FILES)
    }
    appended.await()
    operation.cancelAndJoin()
    assertTrue(operation.isCancelled)
    val partial = requireNotNull(database.blobs.get(f.scope, f.partition, f.draft.id))
    assertEquals(65536L, partial.storedBytes)
    assertNull(partial.completion)
    assertEquals(1, f.readClosures)
    assertEquals(AppResult.Success(true), repository.discardIncomplete(f.access, f.draft.id))
    assertNull(database.blobs.get(f.scope, f.partition, f.draft.id))
    assertFalse(database.blobs.containsRecords())

    val committed = CompletableDeferred<Unit>()
    val lostResult =
        object : BlobStore by database.blobs {
            override suspend fun complete(
                scope: BlobScope,
                partition: String,
                id: String,
                expectedRevision: Long,
                completion: String,
            ): Boolean {
                assertTrue(
                    database.blobs.complete(scope, partition, id, expectedRevision, completion)
                )
                committed.complete(Unit)
                awaitCancellation()
            }
        }
    val recovering = f.repository(lostResult)
    val second = async {
        recovering.import(f.access, f.draft, DOCUMENT_LOCAL_CAPACITY, DOCUMENT_LOCAL_FILES)
    }
    committed.await()
    second.cancelAndJoin()
    assertEquals(AppResult.Success(false), recovering.discardIncomplete(f.access, f.draft.id))
    assertNotNull(
        assertIs<AppResult.Success<LocalDocumentFile?>>(recovering.find(f.access, f.draft.id))
            .value
            ?.sha256
    )
}

suspend fun verifyDocumentCleanupRace(database: AppDatabase) = coroutineScope {
    val f = DocumentFileStorageFixture(database)
    f.activate()
    val aboutToComplete = CompletableDeferred<Unit>()
    val allowComplete = CompletableDeferred<Unit>()
    val cleanupRead = CompletableDeferred<Unit>()
    val returnOldRead = CompletableDeferred<Unit>()
    var holdCleanup = false
    val raced =
        object : BlobStore by database.blobs {
            override suspend fun get(scope: BlobScope, partition: String, id: String): BlobRecord? {
                val row = database.blobs.get(scope, partition, id)
                if (holdCleanup) {
                    cleanupRead.complete(Unit)
                    returnOldRead.await()
                }
                return row
            }

            override suspend fun complete(
                scope: BlobScope,
                partition: String,
                id: String,
                expectedRevision: Long,
                completion: String,
            ): Boolean {
                aboutToComplete.complete(Unit)
                allowComplete.await()
                return database.blobs.complete(scope, partition, id, expectedRevision, completion)
            }
        }
    val repository = f.repository(raced)
    val copying = async {
        repository.import(f.access, f.draft, DOCUMENT_LOCAL_CAPACITY, DOCUMENT_LOCAL_FILES)
    }
    aboutToComplete.await()
    holdCleanup = true
    val cleanup = async { repository.discardIncomplete(f.access, f.draft.id) }
    cleanupRead.await()
    allowComplete.complete(Unit)
    val stored = assertIs<AppResult.Success<DocumentFileImport>>(copying.await())
    returnOldRead.complete(Unit)
    assertEquals(AppResult.Success(false), cleanup.await())
    assertNotNull(assertIs<DocumentFileImport.Stored>(stored.value).file.sha256)
    assertNotNull(database.blobs.get(f.scope, f.partition, f.draft.id)?.completion)
}

suspend fun verifyDocumentImportBoundsAndKeyLoss(database: AppDatabase) {
    val f = DocumentFileStorageFixture(database)
    f.activate()
    val repository = f.repository()
    assertEquals(
        AppResult.Success(DocumentFileImport.CapacityExceeded),
        repository.import(f.access, f.draft, f.draft.declaration.size - 1, 1),
    )
    assertEquals(0, f.reads)
    assertFalse(database.blobs.containsRecords())
    f.readFile = { flow { emit(f.bytes.copyOfRange(0, 65536)) } }
    val short =
        assertIs<AppResult.Failed>(
            repository.import(f.access, f.draft, DOCUMENT_LOCAL_CAPACITY, DOCUMENT_LOCAL_FILES)
        )
    assertEquals("client.file_changed", short.failure.code)
    assertNull(database.blobs.get(f.scope, f.partition, f.draft.id)?.completion)
    assertEquals(AppResult.Success(true), repository.discardIncomplete(f.access, f.draft.id))
    f.readFile = f::completeFile
    assertIs<AppResult.Success<DocumentFileImport>>(
        repository.import(f.access, f.draft, DOCUMENT_LOCAL_CAPACITY, DOCUMENT_LOCAL_FILES)
    )
    val retained = database.blobs.get(f.scope, f.partition, f.draft.id)
    f.keys.write(null)
    assertIs<AppResult.Failed>(repository.find(f.access, f.draft.id))
    assertIs<AppResult.Failed>(
        repository.import(
            f.access,
            f.draft.copy(id = "88888888-8888-8888-8888-888888888888"),
            DOCUMENT_LOCAL_CAPACITY,
            DOCUMENT_LOCAL_FILES,
        )
    )
    assertNull(f.keys.read(), "A missing key over retained ciphertext must not be replaced")
    assertEquals(retained, database.blobs.get(f.scope, f.partition, f.draft.id))
    assertEquals(1, database.blobs.observe(f.scope, f.partition).first().size)
}

private class DocumentFileStorageFixture(private val database: AppDatabase) {
    val access =
        DocumentAccess(
            "session",
            "11111111-1111-1111-1111-111111111111",
            "https://hris.example.test/",
            "22222222-2222-2222-2222-222222222222",
            setOf("documents.self.read", "documents.self.upload"),
            Instant.parse("2026-10-13T00:00:00Z"),
            Instant.parse("2026-10-12T01:00:00Z"),
            "client.workspace_expired",
        )
    val bytes = ByteArray(2 * 65536 + 17) { (it % 251).toByte() }
    val draft =
        DocumentFileDraft(
            "33333333-3333-3333-3333-333333333333",
            DocumentFileDeclaration("document.pdf", "application/pdf", bytes.size.toLong()),
            Instant.parse("2026-10-12T00:00:00Z"),
        )
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    val scope = BlobScope(access.sessionId, accountStorageKey(access.audience, access.accountId))
    val partition = documentFilePartition(access)
    var reads = 0
    var readClosures = 0
    var readFile: () -> Flow<ByteArray> = ::completeFile
    private val inputs =
        object : FileInputSource {
            override suspend fun select(
                requestId: String,
                mediaTypes: Set<String>,
            ): FileInputMetadata? = error("Import consumes an already selected input")

            override fun read(requestId: String): Flow<ByteArray> = flow {
                reads++
                try {
                    emitAll(readFile())
                } finally {
                    readClosures++
                }
            }

            override suspend fun release(requestId: String) = Unit

            override fun close() = Unit
        }

    fun completeFile(): Flow<ByteArray> = flow {
        for (offset in bytes.indices step 65536) emit(
            bytes.copyOfRange(offset, minOf(bytes.size, offset + 65536))
        )
    }

    fun repository(blobs: BlobStore = database.blobs) =
        EncryptedDocumentFileRepository(inputs, blobs, cipher, keys)

    suspend fun activate() = database.accounts.activate(scope.sessionId, scope.accountKey)

    fun associatedData(offset: Long): ByteArray =
        Json.encodeToString(
                listOf(
                    "hris-document-file-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    draft.id,
                    draft.declaration.size.toString(),
                    "block",
                    offset.toString(),
                )
            )
            .encodeToByteArray()
}
