package dev.fajar.starter.documents.data.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.encoding.*
import dev.fajar.starter.documents.domain.entities.*
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
    ): AppResult<DocumentFileImport> = safeFileCall {
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
                        DocumentFileImport.Stored(file)
                    else DocumentFileImport.Conflict
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
                    BlobInsertResult.ScopeChanged -> return@withLock DocumentFileImport.ScopeChanged
                    BlobInsertResult.CapacityExceeded ->
                        return@withLock DocumentFileImport.CapacityExceeded
                    BlobInsertResult.AlreadyStored -> return@withLock DocumentFileImport.Conflict
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
                                return@coroutineScope DocumentFileImport.Conflict
                            storedBytes += length
                            revision++
                        }
                        if (storedBytes != draft.declaration.size) throw FileInputChangedException()
                        val sha256 = digest.finishHex()
                        val completion = codec.completion(access, draft, sha256, key)
                        if (!blobs.complete(scope, partition, draft.id, revision, completion))
                            return@coroutineScope DocumentFileImport.Conflict
                        DocumentFileImport.Stored(
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
