package dev.fajar.starter.documents.domain.replica

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.DocumentFixture
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.documents.domain.replica.repositories.DocumentCatalogRepository
import dev.fajar.starter.documents.domain.replica.usecases.*
import dev.fajar.starter.documents.domain.repositories.DocumentRepository
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.entities.CompanyPolicy
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.*

class CatalogSyncFixture {
    val base = DocumentFixture()
    val workspace = base.workspace
    val build = AppBuild(AppPlatform.Android, 1, "1", null)
    var now
        get() = base.now
        set(value) {
            base.now = value
        }

    var policyCall: suspend () -> AppResult<CompanyPolicy> = {
        AppResult.Success(base.policy.copy(serverTime = now, validUntil = now + 1.hours))
    }

    init {
        base.policyCall = { policyCall() }
    }

    val access =
        (admitDocumentCatalog(workspace.id, workspace, base.session, build, now)
                as AppResult.Success)
            .value
    val store = Records()
    val employment =
        DocumentEmployment(base.query.employmentId, "EMP-001", DocumentEmploymentStatus.ACTIVE, 0)
    val rows = (1..45).associate { catalogId(it) to record(it) }.toMutableMap()
    val reads = mutableListOf<String>()
    var employmentReads = 0
    var employmentCall: suspend (String?) -> AppResult<DocumentEmploymentPage> = {
        AppResult.Success(
            DocumentEmploymentPage(listOf(employment), "2026-10-12", "Asia/Jakarta", null)
        )
    }
    var recordCall: suspend (String) -> AppResult<DocumentCatalogRecord> = { id ->
        rows[id]?.let { AppResult.Success(it) }
            ?: documentFailure("document_evidence_not_found", FailureKind.Unexpected)
    }
    val remote =
        object : DocumentRepository by base.repository {
            override suspend fun employments(
                access: DocumentAccess,
                after: String?,
            ): AppResult<DocumentEmploymentPage> {
                employmentReads++
                return employmentCall(after)
            }

            override suspend fun record(
                access: DocumentAccess,
                id: String,
            ): AppResult<DocumentCatalogRecord> {
                reads += id
                return recordCall(id)
            }
        }
    var bootstrapReads = 0
    var changeReads = 0
    var bootstrapCall: suspend (String?) -> AppResult<SyncBootstrapPage> = {
        AppResult.Success(
            SyncBootstrapPage(
                setOf(SyncCollection.DOCUMENT_EVIDENCE),
                rows.values.map {
                    SyncReference(SyncCollection.DOCUMENT_EVIDENCE, it.id, it.version)
                },
                null,
                "head",
                now,
            )
        )
    }
    var changesCall: suspend (String) -> AppResult<SyncChangePage> = {
        AppResult.Success(SyncChangePage(emptyList(), "head", false, false, 5, now))
    }
    val feed =
        object : SyncFeedRepository {
            override suspend fun bootstrap(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String?,
                limit: Int,
            ): AppResult<SyncBootstrapPage> {
                check(collections == setOf(SyncCollection.DOCUMENT_EVIDENCE) && limit == 50)
                bootstrapReads++
                return bootstrapCall(cursor)
            }

            override suspend fun changes(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String,
                limit: Int,
            ): AppResult<SyncChangePage> {
                check(collections == setOf(SyncCollection.DOCUMENT_EVIDENCE) && limit == 50)
                changeReads++
                return changesCall(cursor)
            }
        }
    val sync =
        SyncDocumentCatalog(
            feed,
            remote,
            store,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            build,
        )
    val observe =
        ObserveDocumentCatalog(
            store,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            build,
        )
    val selection = DocumentCatalogSelection(employment.id)

    fun record(index: Int, version: Long = 0) =
        DocumentCatalogRecord(
            catalogId(index),
            version,
            base.query.employmentId,
            DocumentClassification.PERSONAL,
            base.evidence.copy(
                documentId = catalogId(index),
                revisionId = catalogId(index + 1000),
                title = "Document $index",
            ),
        )

    data class Stored(
        val checkpoint: DocumentCatalogCheckpoint?,
        val records: List<DocumentCatalogRecord>,
    )

    class Records : DocumentCatalogRepository {
        var checkpoint: DocumentCatalogCheckpoint? = null
        var records = linkedMapOf<String, DocumentCatalogEntry>()
        val snapshot = MutableStateFlow(Stored(null, emptyList()))
        val accessState =
            MutableStateFlow<AppResult<DocumentCatalogReadAdmission>>(
                AppResult.Success(DocumentCatalogReadAdmission(null, null))
            )
        var failedClear = false
        private var revision = 0

        override fun observeAdmission(access: DocumentAccess) = accessState

        override suspend fun admission(access: DocumentAccess) = accessState.value

        override suspend fun restrict(
            access: DocumentAccess,
            failure: Failure,
        ): AppResult<Boolean> {
            accessState.value =
                AppResult.Success(DocumentCatalogReadAdmission((++revision).toString(), failure))
            return AppResult.Success(true)
        }

        override suspend fun admit(
            access: DocumentAccess,
            expectedRevision: String?,
        ): AppResult<Boolean> {
            if ((accessState.value as AppResult.Success).value.revision != expectedRevision)
                return AppResult.Success(false)
            accessState.value = AppResult.Success(DocumentCatalogReadAdmission(null, null))
            return AppResult.Success(true)
        }

        override fun observe(access: DocumentAccess, selection: DocumentCatalogSelection) =
            snapshot.map { stored ->
                val after = selection.after
                val rows =
                    stored.records
                        .filter {
                            it.employmentId == selection.employmentId &&
                                it.classification == selection.classification &&
                                (after == null || it.id > after)
                        }
                        .sortedBy { it.id }
                val page = rows.take(20)
                AppResult.Success(
                    DocumentCatalogSnapshot(
                        stored.checkpoint,
                        selection,
                        DocumentEvidencePage(
                            page.map { it.evidence },
                            if (rows.size > 20) page.last().id else null,
                        ),
                    )
                )
            }

        override fun observePublishedCheckpoint(access: DocumentAccess) =
            snapshot.map { AppResult.Success(it.checkpoint) }

        override suspend fun checkpoint(access: DocumentAccess) = AppResult.Success(checkpoint)

        override suspend fun begin(
            checkpoint: DocumentCatalogCheckpoint,
            expectedGeneration: String?,
        ): AppResult<Boolean> {
            if (this.checkpoint?.generation != expectedGeneration) return AppResult.Success(false)
            this.checkpoint = checkpoint
            records = linkedMapOf()
            return AppResult.Success(true)
        }

        override suspend fun commit(
            previous: DocumentCatalogCheckpoint,
            next: DocumentCatalogCheckpoint,
            entries: List<DocumentCatalogEntry>,
        ): AppResult<Boolean> {
            if (checkpoint != previous) return AppResult.Success(false)
            for (entry in entries) {
                val old = records[entry.reference.id]
                if (
                    old == null ||
                        entry.reference.version > old.reference.version ||
                        (entry.reference.version == old.reference.version && entry.record == null)
                )
                    records[entry.reference.id] = entry
            }
            checkpoint = next
            if (next.published)
                snapshot.value = Stored(next, records.values.mapNotNull { it.record })
            return AppResult.Success(true)
        }

        override suspend fun clear(access: DocumentAccess): AppResult<Boolean> {
            if (failedClear) return AppResult.Failed(Failure(FailureKind.Storage, "Unavailable"))
            checkpoint = null
            records.clear()
            snapshot.value = Stored(null, emptyList())
            return AppResult.Success(true)
        }
    }
}

fun catalogId(index: Int) = "91000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
