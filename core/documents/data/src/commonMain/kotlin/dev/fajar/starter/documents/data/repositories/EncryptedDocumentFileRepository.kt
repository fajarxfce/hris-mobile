package dev.fajar.starter.documents.data.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.encoding.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.repositories.DocumentFileRepository
import dev.fajar.starter.fileio.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.*

@Single
class EncryptedDocumentFileRepository(
    private val inputs: FileInputSource,
    private val blobs: BlobStore,
    private val cipher: ContentCipher,
    @Named("file-content-key") private val credentials: AtomicCredentialStore,
) : DocumentFileRepository {
    private val imports = Mutex()
    private val codec = DocumentFileCodec(cipher)

    override suspend fun import(
        access: DocumentAccess,
        draft: DocumentFileDraft,
        maxBytes: Long,
        maxFiles: Int,
    ): AppResult<DocumentFileReservation> = safeFileCall {
        require(draft.download == null)
        // Serialize local imports so key creation and an input's immutable reservation have one
        // writer.
        imports.withLock {
            val scope = documentBlobScope(access)
            val partition = documentFilePartition(access)
            val previous = blobs.get(scope, partition, draft.id)
            val key = if (previous == null) acquireContentKey() else readContentKey()
            try {
                if (previous != null) {
                    val file = codec.decode(access, previous, key)
                    return@withLock if (file.draft == draft && file.sha256 != null)
                        DocumentFileReservation.Stored(file)
                    else DocumentFileReservation.Conflict
                }
                val metadata = codec.declaration(access, draft, key)
                when (
                    blobs.insert(
                        scope,
                        BlobRecord(partition, draft.id, draft.declaration.size, metadata),
                        maxBytes,
                        maxFiles,
                    )
                ) {
                    BlobInsertResult.ScopeChanged ->
                        return@withLock DocumentFileReservation.ScopeChanged
                    BlobInsertResult.CapacityExceeded ->
                        return@withLock DocumentFileReservation.CapacityExceeded
                    BlobInsertResult.AlreadyStored ->
                        return@withLock DocumentFileReservation.Conflict
                    BlobInsertResult.Stored -> Unit
                }
                coroutineScope {
                    val source = inputs.read(draft.id).buffer(0).produceIn(this)
                    val digest = Sha256Digest()
                    var storedBytes = 0L
                    var revision = 0L
                    try {
                        for (bytes in source) {
                            currentCoroutineContext().ensureActive()
                            val length = bytes.size
                            val expected =
                                minOf(
                                    FILE_INPUT_BLOCK_BYTES.toLong(),
                                    draft.declaration.size - storedBytes,
                                )
                            if (length.toLong() != expected || expected <= 0) {
                                bytes.fill(0)
                                throw FileInputChangedException()
                            }
                            digest.update(bytes)
                            val encrypted = codec.block(access, draft, storedBytes, bytes, key)
                            if (
                                !blobs.append(
                                    scope,
                                    partition,
                                    draft.id,
                                    revision,
                                    BlobChunkRecord(storedBytes, length, encrypted),
                                )
                            )
                                return@coroutineScope DocumentFileReservation.Conflict
                            storedBytes += length
                            revision++
                        }
                        if (storedBytes != draft.declaration.size) throw FileInputChangedException()
                        val sha256 = digest.finishHex()
                        val completion = codec.completion(access, draft, sha256, key)
                        if (!blobs.complete(scope, partition, draft.id, revision, completion))
                            return@coroutineScope DocumentFileReservation.Conflict
                        DocumentFileReservation.Stored(
                            LocalDocumentFile(draft, storedBytes, sha256, revision + 1)
                        )
                    } finally {
                        source.cancel()
                    }
                }
            } finally {
                key.fill(0)
            }
        }
    }

    override fun observe(access: DocumentAccess) =
        safeStorageFlow(
            blobs.observe(documentBlobScope(access), documentFilePartition(access), limit = 20),
            mapValue = { rows ->
                if (rows.isEmpty()) emptyList()
                else {
                    val key = readContentKey()
                    try {
                        rows.map { codec.decode(access, it, key) }
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override suspend fun find(access: DocumentAccess, id: String) = safeStorageCall {
        val row = blobs.get(documentBlobScope(access), documentFilePartition(access), id)
        if (row == null) null
        else {
            val key = readContentKey()
            try {
                codec.decode(access, row, key)
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun remove(access: DocumentAccess, id: String, revision: Long) =
        safeStorageCall {
            blobs.remove(documentBlobScope(access), documentFilePartition(access), id, revision)
        }

    override suspend fun updateUpload(
        access: DocumentAccess,
        expected: LocalDocumentFile,
        upload: DocumentUpload,
    ) = safeStorageCall {
        if (expected.draft.download != null) return@safeStorageCall false
        val scope = documentBlobScope(access)
        val partition = documentFilePartition(access)
        val row = blobs.get(scope, partition, expected.draft.id) ?: return@safeStorageCall false
        if (row.revision != expected.revision || row.completion == null)
            return@safeStorageCall false
        val key = readContentKey()
        try {
            val actual = codec.decode(access, row, key)
            if (
                actual.draft != expected.draft ||
                    actual.sha256 != expected.sha256 ||
                    (actual.upload?.intent?.let { it != upload.intent } == true)
            )
                return@safeStorageCall false
            val encrypted = codec.upload(access, actual, upload, key)
            blobs.updateState(scope, partition, row.id, row.revision, encrypted)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun chunk(access: DocumentAccess, file: LocalDocumentFile, offset: Long) =
        safeStorageCall {
            val size = file.draft.declaration.size
            require(
                file.sha256 != null &&
                    offset in 0 until size &&
                    offset % DOCUMENT_UPLOAD_CHUNK_BYTES == 0L
            )
            val scope = documentBlobScope(access)
            val partition = documentFilePartition(access)
            val row = requireNotNull(blobs.get(scope, partition, file.draft.id))
            val key = readContentKey()
            val bytes =
                ByteArray(minOf(DOCUMENT_UPLOAD_CHUNK_BYTES.toLong(), size - offset).toInt())
            try {
                val actual = codec.decode(access, row, key)
                require(
                    actual.draft == file.draft &&
                        actual.sha256 == file.sha256 &&
                        actual.storedBytes == size
                )
                val digest = Sha256Digest()
                var copied = 0
                while (copied < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val position = offset + copied
                    val block =
                        requireNotNull(blobs.chunk(scope, partition, file.draft.id, position))
                    require(
                        block.offset == position &&
                            block.length == minOf(FILE_INPUT_BLOCK_BYTES, bytes.size - copied)
                    )
                    val plaintext = codec.readBlock(access, file.draft, block, key)
                    try {
                        digest.update(plaintext)
                        plaintext.copyInto(bytes, copied)
                        copied += plaintext.size
                    } finally {
                        plaintext.fill(0)
                    }
                }
                currentCoroutineContext().ensureActive()
                DocumentFileChunk(bytes, digest.finishHex())
            } catch (cause: Throwable) {
                bytes.fill(0)
                throw cause
            } finally {
                key.fill(0)
            }
        }

    override suspend fun discardIncomplete(access: DocumentAccess, id: String) = safeStorageCall {
        val scope = documentBlobScope(access)
        val partition = documentFilePartition(access)
        val row = blobs.get(scope, partition, id)
        when {
            row == null -> true
            row.completion != null -> false
            else -> blobs.remove(scope, partition, id, row.revision)
        }
    }

    override suspend fun reserveDownload(
        access: DocumentAccess,
        draft: DocumentFileDraft,
        maxBytes: Long,
        maxFiles: Int,
    ): AppResult<DocumentFileReservation> = safeStorageCall {
        val source = requireNotNull(draft.download)
        require(validateDocumentDownload(source) is AppResult.Success)
        require(
            draft.declaration ==
                DocumentFileDeclaration(
                    source.evidence.fileName,
                    source.evidence.mediaType,
                    source.evidence.size,
                )
        )
        imports.withLock {
            val scope = documentBlobScope(access)
            val partition = documentFilePartition(access)
            val previous = blobs.get(scope, partition, draft.id)
            val key = if (previous == null) acquireContentKey() else readContentKey()
            try {
                if (previous != null) {
                    val file = codec.decode(access, previous, key)
                    return@withLock if (
                        file.draft.download == source && file.draft.declaration == draft.declaration
                    )
                        DocumentFileReservation.Stored(file)
                    else DocumentFileReservation.Conflict
                }
                val metadata = codec.declaration(access, draft, key)
                when (
                    blobs.insert(
                        scope,
                        BlobRecord(partition, draft.id, draft.declaration.size, metadata),
                        maxBytes,
                        maxFiles,
                    )
                ) {
                    BlobInsertResult.ScopeChanged -> DocumentFileReservation.ScopeChanged
                    BlobInsertResult.CapacityExceeded -> DocumentFileReservation.CapacityExceeded
                    BlobInsertResult.AlreadyStored -> DocumentFileReservation.Conflict
                    BlobInsertResult.Stored ->
                        DocumentFileReservation.Stored(LocalDocumentFile(draft, 0, null, 0))
                }
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun appendDownload(
        access: DocumentAccess,
        file: LocalDocumentFile,
        bytes: ByteArray,
    ): AppResult<LocalDocumentFile?> = safeStorageCall {
        require(file.draft.download != null && file.sha256 == null)
        require(file.storedBytes % FILE_INPUT_BLOCK_BYTES == 0L)
        require(
            bytes.isNotEmpty() &&
                bytes.size.toLong() ==
                    minOf(
                        DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(),
                        file.draft.declaration.size - file.storedBytes,
                    )
        )
        val scope = documentBlobScope(access)
        val partition = documentFilePartition(access)
        val row = blobs.get(scope, partition, file.draft.id) ?: return@safeStorageCall null
        val key = readContentKey()
        try {
            if (codec.decode(access, row, key) != file) return@safeStorageCall null
            var storedBytes = file.storedBytes
            var revision = file.revision
            for (offset in bytes.indices step FILE_INPUT_BLOCK_BYTES) {
                currentCoroutineContext().ensureActive()
                val block =
                    bytes.copyOfRange(offset, minOf(bytes.size, offset + FILE_INPUT_BLOCK_BYTES))
                val length = block.size
                val encrypted = codec.block(access, file.draft, storedBytes, block, key)
                if (
                    !blobs.append(
                        scope,
                        partition,
                        file.draft.id,
                        revision,
                        BlobChunkRecord(storedBytes, length, encrypted),
                    )
                )
                    return@safeStorageCall null
                storedBytes += length
                revision++
            }
            file.copy(storedBytes = storedBytes, revision = revision)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun completeDownload(
        access: DocumentAccess,
        file: LocalDocumentFile,
    ): AppResult<DocumentFileCompletion> = safeStorageCall {
        val source = requireNotNull(file.draft.download)
        require(file.storedBytes == file.draft.declaration.size)
        val scope = documentBlobScope(access)
        val partition = documentFilePartition(access)
        val row =
            blobs.get(scope, partition, file.draft.id)
                ?: return@safeStorageCall DocumentFileCompletion.Changed
        val key = readContentKey()
        try {
            val actual = codec.decode(access, row, key)
            if (actual != file) return@safeStorageCall DocumentFileCompletion.Changed
            if (actual.sha256 != null) return@safeStorageCall DocumentFileCompletion.Ready(actual)
            val digest = Sha256Digest()
            var offset = 0L
            while (offset < file.storedBytes) {
                currentCoroutineContext().ensureActive()
                val block = requireNotNull(blobs.chunk(scope, partition, file.draft.id, offset))
                require(
                    block.offset == offset &&
                        block.length.toLong() ==
                            minOf(FILE_INPUT_BLOCK_BYTES.toLong(), file.storedBytes - offset)
                )
                val bytes = codec.readBlock(access, file.draft, block, key)
                try {
                    digest.update(bytes)
                    offset += bytes.size
                } finally {
                    bytes.fill(0)
                }
            }
            val sha256 = digest.finishHex()
            if (sha256 != source.evidence.sha256)
                return@safeStorageCall DocumentFileCompletion.ChecksumMismatch
            val proof = codec.completion(access, file.draft, sha256, key)
            if (!blobs.complete(scope, partition, file.draft.id, file.revision, proof))
                DocumentFileCompletion.Changed
            else
                DocumentFileCompletion.Ready(
                    file.copy(sha256 = sha256, revision = file.revision + 1)
                )
        } finally {
            key.fill(0)
        }
    }

    private suspend fun readContentKey(): ByteArray =
        Base64.decode(requireNotNull(credentials.read()) { "File content key is unavailable." })
            .also { require(it.size == 32) }

    /** Storage key creation never replaces a missing key over retained file ciphertext. */
    private suspend fun acquireContentKey(): ByteArray {
        val existing = credentials.read()
        if (existing != null) return Base64.decode(existing).also { require(it.size == 32) }
        if (blobs.containsRecords()) return readContentKey()
        val generated = cipher.generateKey()
        return try {
            Base64.decode(credentials.writeIfAbsent(Base64.encode(generated))).also {
                require(it.size == 32)
            }
        } finally {
            generated.fill(0)
        }
    }
}

private fun documentBlobScope(access: DocumentAccess) =
    BlobScope(access.sessionId, accountStorageKey(access.audience, access.accountId))
