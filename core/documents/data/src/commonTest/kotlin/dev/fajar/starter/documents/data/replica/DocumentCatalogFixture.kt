package dev.fajar.starter.documents.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.DocumentDataFixture
import dev.fajar.starter.documents.data.replica.datasources.MemoryDocumentCatalogReadAdmissionDataSource
import dev.fajar.starter.documents.data.replica.mappers.documentCatalogReplicaScope
import dev.fajar.starter.documents.data.replica.repositories.EncryptedDocumentCatalogRepository
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.documents.domain.replica.policies.initialDocumentCatalogCheckpoint
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.flow.first

class DocumentCatalogFixture(private val openDatabase: () -> AppDatabase) {
    private val base = DocumentDataFixture()
    var db = openDatabase()
    val now = base.now
    val access = base.access
    val employments =
        listOf(
            DocumentEmployment(
                catalogFixtureId(9001),
                "EMP-001",
                DocumentEmploymentStatus.ACTIVE,
                0,
            ),
            DocumentEmployment(
                catalogFixtureId(9002),
                "EMP-002",
                DocumentEmploymentStatus.ACTIVE,
                0,
            ),
        )
    val selected = DocumentCatalogSelection(employments.first().id)
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    var admissions = MemoryDocumentCatalogReadAdmissionDataSource()
    var repository = EncryptedDocumentCatalogRepository(db.replicas, cipher, keys, admissions)
    val record = record(1)

    fun record(
        index: Int,
        version: Long = 0,
        employment: String = employments.first().id,
        category: DocumentClassification = DocumentClassification.PERSONAL,
    ) =
        DocumentCatalogRecord(
            catalogFixtureId(index),
            version,
            employment,
            category,
            DocumentEvidence(
                catalogFixtureId(index),
                catalogFixtureId(index + 10000),
                1,
                "Document $index",
                "document-$index.pdf",
                "application/pdf",
                100,
                "a".repeat(64),
                now,
            ),
        )

    suspend fun activate(owner: DocumentAccess = access) =
        db.accounts.activate(owner.sessionId, documentCatalogReplicaScope(owner).accountKey)

    suspend fun checkpoint(owner: DocumentAccess = access) =
        assertIs<AppResult.Success<DocumentCatalogCheckpoint?>>(repository.checkpoint(owner)).value

    suspend fun publish(records: List<DocumentCatalogRecord>, publish: Boolean = true) {
        var checkpoint = checkpoint()
        if (checkpoint == null) {
            checkpoint = initialDocumentCatalogCheckpoint(access, "generation", now, employments)
            assertEquals(AppResult.Success(true), repository.begin(checkpoint, null))
        }
        for ((index, batch) in records.chunked(50).ifEmpty { listOf(emptyList()) }.withIndex()) {
            val previous = requireNotNull(checkpoint)
            val visible = previous.published || (publish && (index + 1) * 50 >= records.size)
            val next =
                previous.copy(
                    revision = previous.revision + 1,
                    phase = DocumentCatalogSyncPhase.CHANGES,
                    cursor = "head",
                    published = visible,
                    refreshedAt = if (visible) now else null,
                )
            assertEquals(
                AppResult.Success(true),
                repository.commit(
                    previous,
                    next,
                    batch.map {
                        DocumentCatalogEntry(
                            SyncReference(SyncCollection.DOCUMENT_EVIDENCE, it.id, it.version),
                            it,
                        )
                    },
                ),
            )
            checkpoint = next
        }
    }

    suspend fun snapshot(
        owner: DocumentAccess = access,
        selection: DocumentCatalogSelection = selected,
    ) =
        assertIs<AppResult.Success<DocumentCatalogSnapshot>>(
                repository.observe(owner, selection).first()
            )
            .value

    suspend fun reopen(owner: DocumentAccess = access) {
        db.close()
        db = openDatabase()
        admissions = MemoryDocumentCatalogReadAdmissionDataSource()
        repository = EncryptedDocumentCatalogRepository(db.replicas, cipher, keys, admissions)
        activate(owner)
    }

    fun close() {
        db.close()
        keys.close()
    }
}

fun catalogFixtureId(index: Int) = "91000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
