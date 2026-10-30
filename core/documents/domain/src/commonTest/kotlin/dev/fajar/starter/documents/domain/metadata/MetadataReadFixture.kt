package dev.fajar.starter.documents.domain.metadata

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.repositories.*
import dev.fajar.starter.documents.domain.metadata.usecases.*
import dev.fajar.starter.documents.domain.replica.CatalogSyncFixture
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

class MetadataReadFixture {
    val catalog = CatalogSyncFixture()
    val base = catalog.base
    val id = base.evidence.documentId
    val metadata =
        DocumentBusinessMetadata(DocumentCategory.PASSPORT, "PRIVATE-FIXTURE", "AU", null, null)
    val head =
        DocumentMetadataHead(
            id,
            base.query.employmentId,
            "Passport",
            DocumentClassification.PERSONAL,
            1,
            3,
            base.session.user.id,
            base.now,
            null,
            metadata,
            2,
            true,
        )
    val page =
        DocumentMetadataPage(
            (0L..2L).map {
                DocumentMetadataRevision(
                    id,
                    it,
                    it + 1,
                    metadata,
                    base.session.user.id,
                    base.now,
                    if (it == 0L) null else "Correction",
                )
            },
            null,
        )
    var headReads = 0
    var historyReads = 0
    var saves = 0
    var headCall: suspend () -> AppResult<DocumentMetadataHead> = { AppResult.Success(head) }
    var historyCall: suspend (Long?) -> AppResult<DocumentMetadataPage> = { after ->
        AppResult.Success(page.copy(items = page.items.filter { it.version > (after ?: -1L) }))
    }
    val cache = MetadataReadCache()
    val admission = MetadataReadAdmission()
    val mutex = Mutex()

    init {
        catalog.employmentCall = {
            AppResult.Success(
                DocumentEmploymentPage(
                    listOf(catalog.employment),
                    "2026-10-12",
                    "Asia/Jakarta",
                    null,
                    true,
                )
            )
        }
    }

    private val remote =
        object : DocumentMetadataRepository {
            override suspend fun head(
                access: DocumentAccess,
                documentId: String,
            ): AppResult<DocumentMetadataHead> {
                headReads++
                return headCall()
            }

            override suspend fun history(
                access: DocumentAccess,
                documentId: String,
                after: Long?,
            ): AppResult<DocumentMetadataPage> {
                historyReads++
                return historyCall(after)
            }

            override suspend fun save(
                access: DocumentAccess,
                change: DocumentMetadataChange,
            ): AppResult<DocumentMutationReceipt> {
                saves++
                error("Reads never save corrections")
            }
        }
    val refresh =
        RefreshDocumentMetadata(
            remote,
            catalog.remote,
            cache,
            admission,
            catalog.store,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            catalog.build,
            mutex,
        )
    val observe =
        ObserveDocumentMetadata(
            cache,
            admission,
            catalog.store,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            catalog.build,
        )
}

class MetadataReadCache : DocumentMetadataCacheRepository {
    val stored = MutableStateFlow<DocumentMetadataCheckpoint?>(null)
    val rows = mutableMapOf<Long, DocumentMetadataRevision>()
    var activeObservers = 0
    var observations = 0
    var completedFailure: Failure? = null
    var beforeCommit: suspend () -> Unit = {}
    var rejectCommit = false

    override fun observe(
        access: DocumentAccess,
        documentId: String,
        after: Long?,
    ): Flow<AppResult<DocumentMetadataSnapshot>> = flow {
        observations++
        val failure = completedFailure
        if (failure != null) {
            emit(AppResult.Failed(failure))
            return@flow
        }
        activeObservers++
        try {
            emitAll(
                stored.map { checkpoint ->
                    val selected =
                        rows.values
                            .filter { it.version > (after ?: -1L) }
                            .sortedBy { it.version }
                            .take(20)
                    AppResult.Success(
                        DocumentMetadataSnapshot(
                            checkpoint,
                            after,
                            DocumentMetadataPage(
                                selected,
                                selected.lastOrNull()?.version?.takeIf {
                                    it < (checkpoint?.head?.metadataVersion ?: -1L)
                                },
                            ),
                            true,
                        )
                    )
                }
            )
        } finally {
            activeObservers--
        }
    }

    override suspend fun checkpoint(access: DocumentAccess, documentId: String) =
        AppResult.Success(stored.value)

    override suspend fun commit(
        access: DocumentAccess,
        expected: DocumentMetadataCheckpoint?,
        next: DocumentMetadataCheckpoint,
        rows: List<DocumentMetadataRevision>,
    ): AppResult<Boolean> {
        beforeCommit()
        if (rejectCommit || expected != stored.value) return AppResult.Success(false)
        rows.forEach { this.rows[it.version] = it }
        stored.value = next
        return AppResult.Success(true)
    }
}

class MetadataReadAdmission : DocumentMetadataAdmissionRepository {
    val state = MutableStateFlow(DocumentMetadataReadAdmission(null, null))
    var failStorage = false
    var beforeAdmit: suspend () -> Unit = {}
    private var revision = 0

    override fun observe(access: DocumentAccess, documentId: String) =
        state.map { AppResult.Success(it) }

    override suspend fun current(access: DocumentAccess, documentId: String) =
        AppResult.Success(state.value)

    override suspend fun restrict(
        access: DocumentAccess,
        documentId: String,
        failure: Failure,
    ): AppResult<Boolean> {
        state.value = DocumentMetadataReadAdmission((++revision).toString(), failure)
        return if (failStorage)
            AppResult.Failed(Failure(FailureKind.Storage, "Fixture storage failure"))
        else AppResult.Success(true)
    }

    override suspend fun admit(
        access: DocumentAccess,
        documentId: String,
        expectedRevision: String?,
    ): AppResult<Boolean> {
        beforeAdmit()
        if (failStorage)
            return AppResult.Failed(Failure(FailureKind.Storage, "Fixture storage failure"))
        if (state.value.revision != expectedRevision) return AppResult.Success(false)
        state.value = DocumentMetadataReadAdmission(null, null)
        return AppResult.Success(true)
    }
}
