package dev.fajar.starter.documents.data.metadata

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.metadata.datasources.MemoryDocumentMetadataAdmissionBuffer
import dev.fajar.starter.documents.data.metadata.encoding.documentMetadataScope
import dev.fajar.starter.documents.data.metadata.mappers.*
import dev.fajar.starter.documents.data.metadata.repositories.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.policies.mergeDocumentMetadataPage
import dev.fajar.starter.securestorage.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.flow.first

class DocumentMetadataStorageFixture(private val open: () -> AppDatabase) {
    val remote = DocumentMetadataRemoteFixture()
    val access = remote.access
    val id = remote.id
    var db = open()
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    var buffer = MemoryDocumentMetadataAdmissionBuffer()
    var cache = EncryptedDocumentMetadataCacheRepository(db.replicas, cipher, keys)
    var admissions = EncryptedDocumentMetadataAdmissionRepository(db.replicas, cipher, keys, buffer)
    private val generation = "77777777-7777-7777-7777-777777777777"

    suspend fun activate(owner: DocumentAccess = access) {
        db.accounts.activate(owner.sessionId, documentMetadataScope(owner).accountKey)
    }

    suspend fun initialize() {
        activate()
        val key = cipher.generateKey()
        try {
            keys.write(Base64.encode(key))
        } finally {
            key.fill(0)
        }
    }

    suspend fun checkpoint(owner: DocumentAccess = access) =
        assertIs<AppResult.Success<DocumentMetadataCheckpoint?>>(cache.checkpoint(owner, id)).value

    suspend fun snapshot(after: Long? = null, owner: DocumentAccess = access) =
        assertIs<AppResult.Success<DocumentMetadataSnapshot>>(
                cache.observe(owner, id, after).first()
            )
            .value

    suspend fun append(latest: Long, owner: DocumentAccess = access) {
        val previous = checkpoint(owner)
        val after = previous?.historyThrough
        val through = minOf(latest, (after ?: -1) + 20)
        val rows = ((after ?: -1) + 1..through).map { remote.revision(it).toMetadataRevision(id) }
        val head =
            remote.head.copy(version = latest + 1, metadataVersion = latest).toMetadataHead(id)
        val next =
            merged(
                previous,
                head,
                DocumentMetadataPage(rows, through.takeIf { it < latest }),
                after,
            )
        assertEquals(AppResult.Success(true), cache.commit(owner, previous, next, rows))
    }

    fun merged(
        previous: DocumentMetadataCheckpoint?,
        head: DocumentMetadataHead,
        page: DocumentMetadataPage,
        after: Long?,
    ) =
        assertIs<AppResult.Success<DocumentMetadataCheckpoint>>(
                mergeDocumentMetadataPage(
                    previous,
                    head,
                    page,
                    after,
                    remote.head.toMetadataHead(id).createdAt,
                    generation,
                )
            )
            .value

    suspend fun reopen(owner: DocumentAccess = access) {
        db.close()
        db = open()
        buffer = MemoryDocumentMetadataAdmissionBuffer()
        cache = EncryptedDocumentMetadataCacheRepository(db.replicas, cipher, keys)
        admissions = EncryptedDocumentMetadataAdmissionRepository(db.replicas, cipher, keys, buffer)
        activate(owner)
    }

    suspend fun close() {
        db.close()
        keys.write(null)
    }
}
