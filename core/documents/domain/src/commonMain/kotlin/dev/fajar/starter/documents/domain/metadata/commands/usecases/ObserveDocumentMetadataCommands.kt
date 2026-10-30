@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.metadata.commands.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.policies.belongsTo
import dev.fajar.starter.documents.domain.metadata.commands.repositories.DocumentMetadataCommandRepository
import dev.fajar.starter.documents.domain.metadata.repositories.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.replica.repositories.DocumentCatalogRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveDocumentMetadataCommands(
    private val commands: DocumentMetadataCommandRepository,
    private val admission: DocumentMetadataAdmissionRepository,
    private val catalog: DocumentCatalogRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        documentId: String? = null,
    ): Flow<AppResult<DocumentMetadataCommandsView>> {
        if (documentId != null && Uuid.parseOrNull(documentId)?.toString() != documentId)
            return flowOf(documentFailure("client.document_metadata_invalid"))
        return sessions
            .observe()
            .flatMapLatest { identity ->
                when (identity) {
                    is AppResult.Failed -> flowOf(identity)
                    is AppResult.Success -> {
                        val session = identity.value
                        if (session == null)
                            flowOf(documentFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { current ->
                                when (current) {
                                    is AppResult.Failed -> current
                                    is AppResult.Success ->
                                        admitDocumentCatalog(
                                            workspaceId,
                                            current.value,
                                            session,
                                            build,
                                            clock.now(),
                                        )
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { access ->
                when (access) {
                    is AppResult.Failed -> flowOf(access)
                    is AppResult.Success -> observeAdmitted(access.value, documentId)
                }
            }
            .buffer(0)
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAdmitted(
        access: DocumentAccess,
        documentId: String?,
    ): Flow<AppResult<DocumentMetadataCommandsView>> =
        catalog.observeAdmission(access).flatMapLatest { module ->
            val denial = (module as? AppResult.Success)?.value?.denial
            when {
                module is AppResult.Failed -> flowOf(module)
                denial != null -> flowOf(AppResult.Failed(denial))
                documentId != null -> observeOne(access, documentId)
                else -> {
                    commands.observeAll(access).flatMapLatest { result ->
                        when (result) {
                            is AppResult.Failed -> flowOf(result)
                            is AppResult.Success -> visible(access, documentId, result.value)
                        }
                    }
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeOne(
        access: DocumentAccess,
        documentId: String,
    ): Flow<AppResult<DocumentMetadataCommandsView>> =
        admission.observe(access, documentId).flatMapLatest { gate ->
            val denial = (gate as? AppResult.Success)?.value?.denial
            when {
                gate is AppResult.Failed -> flowOf(gate)
                denial != null ->
                    flowOf(
                        AppResult.Success(
                            DocumentMetadataCommandsView(access, emptyList(), listOf(denial))
                        )
                    )
                else ->
                    commands.observe(access, documentId).map { result ->
                        currentCoroutineContext().ensureActive()
                        when (result) {
                            is AppResult.Failed -> result
                            is AppResult.Success -> {
                                val record = result.value
                                if (
                                    record != null &&
                                        (!record.command.belongsTo(access) ||
                                            record.command.change.documentId != documentId)
                                )
                                    documentFailure(
                                        "client.document_scope_changed",
                                        FailureKind.AccessDenied,
                                    )
                                else
                                    AppResult.Success(
                                        DocumentMetadataCommandsView(
                                            access,
                                            listOfNotNull(record),
                                            emptyList(),
                                        )
                                    )
                            }
                        }
                    }
            }
        }

    private fun visible(
        access: DocumentAccess,
        documentId: String?,
        records: List<DocumentMetadataCommandRecord>,
    ): Flow<AppResult<DocumentMetadataCommandsView>> {
        if (
            records.size > 128 ||
                records.any {
                    !it.command.belongsTo(access) ||
                        (documentId != null && it.command.change.documentId != documentId)
                } ||
                records.map { it.command.change.documentId }.toSet().size != records.size
        )
            return flowOf(
                documentFailure("client.document_scope_changed", FailureKind.AccessDenied)
            )
        if (records.isEmpty())
            return flowOf(
                AppResult.Success(DocumentMetadataCommandsView(access, emptyList(), emptyList()))
            )
        return combine(
            records.map { record ->
                admission.observe(access, record.command.change.documentId).map { gate ->
                    when (gate) {
                        is AppResult.Failed -> CommandVisibility(null, gate.failure)
                        is AppResult.Success ->
                            if (gate.value.denial != null)
                                CommandVisibility(null, gate.value.denial)
                            else CommandVisibility(record, null)
                    }
                }
            }
        ) { values ->
            currentCoroutineContext().ensureActive()
            AppResult.Success(
                DocumentMetadataCommandsView(
                    access,
                    values.mapNotNull { it.record },
                    values.mapNotNull { it.failure },
                )
            )
        }
    }
}

private data class CommandVisibility(
    val record: DocumentMetadataCommandRecord?,
    val failure: Failure?,
)
