package dev.fajar.starter.documents.domain.metadata.commands

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.MetadataReadFixture
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.policies.belongsTo
import dev.fajar.starter.documents.domain.metadata.commands.repositories.DocumentMetadataCommandRepository
import dev.fajar.starter.documents.domain.metadata.commands.usecases.*
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.repositories.DocumentMetadataRepository
import kotlin.test.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

class MetadataCommandFixture {
    val reads = MetadataReadFixture()
    val base = reads.base

    init {
        base.workspaces.value =
            AppResult.Success(
                base.workspace.copy(
                    permissions = base.workspace.permissions + "documents.self.upload"
                )
            )
    }

    val access =
        reads.catalog.access.copy(
            permissions = base.workspace.permissions + "documents.self.upload"
        )
    val workspaceId = base.workspace.id
    val commands = MemoryMetadataCommands()
    val mutex = Mutex()
    val sent = mutableListOf<DocumentMetadataChange>()
    var saveCall: suspend (DocumentMetadataChange) -> AppResult<DocumentMutationReceipt> = {
        AppResult.Success(DocumentMutationReceipt(it.documentId, it.expectedVersion + 1))
    }
    private val remote =
        object : DocumentMetadataRepository by reads.remote {
            override suspend fun save(
                access: DocumentAccess,
                change: DocumentMetadataChange,
            ): AppResult<DocumentMutationReceipt> {
                sent += change
                return saveCall(change)
            }
        }
    val input = DocumentMetadataInput(DocumentCategory.PASSPORT, "Corrected number", "AU")
    val prepare =
        PrepareDocumentMetadataCommand(
            commands,
            reads.cache,
            reads.admission,
            reads.catalog.store,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            reads.catalog.build,
            mutex,
        )
    val observe =
        ObserveDocumentMetadataCommands(
            commands,
            reads.admission,
            reads.catalog.store,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            reads.catalog.build,
        )
    val review =
        ReviewDocumentMetadataCommand(
            commands,
            reads.cache,
            reads.admission,
            reads.catalog.store,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            reads.catalog.build,
            mutex,
        )

    fun replay() =
        ReplayDocumentMetadataCommand(
            commands,
            remote,
            reads.catalog.remote,
            reads.admission,
            reads.catalog.store,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            reads.catalog.build,
            mutex,
        )

    suspend fun view(): DocumentMetadataView {
        assertIs<AppResult.Success<Unit>>(reads.refresh(workspaceId, reads.id))
        return assertIs<AppResult.Success<DocumentMetadataView>>(
                reads.observe(workspaceId, reads.id, flowOf(null)).first()
            )
            .value
    }

    suspend fun prepared() =
        assertIs<AppResult.Success<DocumentMetadataCommandRecord>>(
                prepare(workspaceId, view(), input, " User correction ")
            )
            .value

    fun record() = commands.records.value.single()
}

class MemoryMetadataCommands : DocumentMetadataCommandRepository {
    val records = MutableStateFlow<List<DocumentMetadataCommandRecord>>(emptyList())
    var beforeInsert: suspend () -> Unit = {}
    var afterUpdate: suspend () -> Unit = {}
    var updateFailureAt: Int? = null
    var updates = 0
    var insertion = DocumentMetadataCommandAdmission.STORED

    override fun observeAll(access: DocumentAccess) =
        records.map { list -> AppResult.Success(list.filter { it.command.belongsTo(access) }) }

    override fun observe(access: DocumentAccess, documentId: String) =
        records.map { list ->
            AppResult.Success(
                list.singleOrNull {
                    it.command.belongsTo(access) && it.command.change.documentId == documentId
                }
            )
        }

    override suspend fun find(access: DocumentAccess, operationId: String) =
        AppResult.Success(
            records.value.singleOrNull {
                it.command.belongsTo(access) && it.command.change.operationId == operationId
            }
        )

    override suspend fun insert(
        access: DocumentAccess,
        command: DocumentMetadataCommand,
    ): AppResult<DocumentMetadataCommandAdmission> {
        beforeInsert()
        if (insertion != DocumentMetadataCommandAdmission.STORED)
            return AppResult.Success(insertion)
        if (
            records.value.any {
                it.command.belongsTo(access) &&
                    it.command.change.documentId == command.change.documentId
            }
        )
            return AppResult.Success(DocumentMetadataCommandAdmission.OCCUPIED)
        records.value +=
            DocumentMetadataCommandRecord(command, 0, 0, DocumentMetadataCommandOutcome.Pending)
        return AppResult.Success(DocumentMetadataCommandAdmission.STORED)
    }

    override suspend fun update(
        access: DocumentAccess,
        expected: DocumentMetadataCommandRecord,
        outcome: DocumentMetadataCommandOutcome,
        attempts: Int,
    ): AppResult<Boolean> {
        updates++
        if (updates == updateFailureAt)
            return AppResult.Failed(Failure(FailureKind.Storage, "Fixture write failure"))
        if (!expected.command.belongsTo(access) || expected !in records.value)
            return AppResult.Success(false)
        records.value =
            records.value.map {
                if (it == expected)
                    it.copy(revision = it.revision + 1, attempts = attempts, outcome = outcome)
                else it
            }
        afterUpdate()
        return AppResult.Success(true)
    }

    override suspend fun remove(
        access: DocumentAccess,
        expected: DocumentMetadataCommandRecord,
    ): AppResult<Boolean> {
        if (!expected.command.belongsTo(access) || expected !in records.value)
            return AppResult.Success(false)
        records.value -= expected
        return AppResult.Success(true)
    }
}
