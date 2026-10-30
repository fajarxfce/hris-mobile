package dev.fajar.starter.documents.domain.metadata.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.commands.repositories.DocumentMetadataCommandRepository
import dev.fajar.starter.documents.domain.metadata.policies.documentMetadataPendingSummary
import dev.fajar.starter.documents.domain.metadata.repositories.DocumentMetadataAdmissionRepository
import dev.fajar.starter.documents.domain.metadata.working.repositories.DocumentMetadataWorkingRepository
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.replica.repositories.DocumentCatalogRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveDocumentMetadataPending(
    private val commands: DocumentMetadataCommandRepository,
    private val copies: DocumentMetadataWorkingRepository,
    private val admission: DocumentMetadataAdmissionRepository,
    private val catalog: DocumentCatalogRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : ObservePendingActions {
    override val group = PendingActionGroup.DocumentCorrections

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePending(workspaceId: String): Flow<AppResult<PendingActionSummary>> =
        sessions
            .observe()
            .flatMapLatest { identity ->
                when (identity) {
                    is AppResult.Failed -> flowOf(identity)
                    is AppResult.Success -> {
                        val session = identity.value
                        if (session == null)
                            flowOf(documentFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { selected ->
                                when (selected) {
                                    is AppResult.Failed -> selected
                                    is AppResult.Success ->
                                        admitDocumentCatalog(
                                            workspaceId,
                                            selected.value,
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
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> observeAdmitted(result.value)
                }
            }
            .buffer(0)
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAdmitted(access: DocumentAccess): Flow<AppResult<PendingActionSummary>> =
        catalog.observeAdmission(access).flatMapLatest { gate ->
            val denial = (gate as? AppResult.Success)?.value?.denial
            when {
                gate is AppResult.Failed -> flowOf(gate)
                denial != null -> flowOf(AppResult.Failed(denial))
                else -> observeStored(access)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeStored(access: DocumentAccess): Flow<AppResult<PendingActionSummary>> =
        flow {
                // A later storage error must not abandon already discovered resource gates.
                // This bounded identity list belongs only to the current access/collector.
                var ids = emptyList<String>()
                combine(commands.observeAll(access), copies.observePage(access, null)) {
                        original,
                        working ->
                        original to working
                    }
                    .collect { (original, working) ->
                        currentCoroutineContext().ensureActive()
                        val summary =
                            when {
                                original is AppResult.Failed -> original
                                working is AppResult.Failed -> working
                                original is AppResult.Success && working is AppResult.Success -> {
                                    val summary =
                                        documentMetadataPendingSummary(
                                            access,
                                            original.value,
                                            working.value,
                                        )
                                    if (summary is AppResult.Success)
                                        ids =
                                            (original.value.map { it.command.change.documentId } +
                                                    working.value.items.map { it.head.id })
                                                .distinct()
                                    summary
                                }
                                else -> error("Unreachable local result")
                            }
                        emit(MetadataPendingRead(ids, summary))
                    }
            }
            .flatMapLatest { read -> gateResources(access, read.ids, read.summary) }

    private fun gateResources(
        access: DocumentAccess,
        ids: List<String>,
        summary: AppResult<PendingActionSummary>,
    ): Flow<AppResult<PendingActionSummary>> {
        if (ids.isEmpty()) return flowOf(summary)
        // At most 128 commands plus the selected twenty drafts, one subscription per document.
        return combine(ids.map { id -> admission.observe(access, id) }) { gates ->
            currentCoroutineContext().ensureActive()
            val failure =
                gates.firstNotNullOfOrNull { (it as? AppResult.Success)?.value?.denial }
                    ?: gates.firstNotNullOfOrNull { (it as? AppResult.Failed)?.failure }
            failure?.let { AppResult.Failed(it) } ?: summary
        }
    }
}

private data class MetadataPendingRead(
    val ids: List<String>,
    val summary: AppResult<PendingActionSummary>,
)
