package dev.fajar.starter.documents.domain.replica.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.documents.domain.replica.policies.*
import dev.fajar.starter.documents.domain.replica.repositories.DocumentCatalogRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveDocumentCatalog(
    private val replicas: DocumentCatalogRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : ObserveCompanyData {
    override val dataSet = CompanyDataSet.Documents

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        selection: Flow<DocumentCatalogSelection>,
    ): Flow<AppResult<DocumentCatalogSnapshot>> =
        observeAdmitted(workspaceId) { access ->
                selection.distinctUntilChanged().flatMapLatest { selected ->
                    val valid = validateDocumentCatalogSelection(selected)
                    if (valid is AppResult.Failed) flowOf(valid)
                    else
                        replicas.observe(access, selected).map { snapshot ->
                            currentCoroutineContext().ensureActive()
                            when (snapshot) {
                                is AppResult.Failed -> snapshot
                                is AppResult.Success -> {
                                    val checkpoint = snapshot.value.checkpoint
                                    if (
                                        checkpoint != null &&
                                            !sameStoredDocumentScope(access, checkpoint.access)
                                    )
                                        documentFailure(
                                            "client.document_scope_changed",
                                            FailureKind.AccessDenied,
                                        )
                                    else snapshot
                                }
                            }
                        }
                }
            }
            .distinctUntilChanged()

    override fun observeStatus(workspaceId: String): Flow<AppResult<CompanyDataStatus>> =
        observeAdmitted(workspaceId) { access ->
                replicas.observePublishedCheckpoint(access).map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> {
                            val checkpoint = result.value
                            if (
                                checkpoint != null &&
                                    !sameStoredDocumentScope(access, checkpoint.access)
                            )
                                documentFailure(
                                    "client.document_scope_changed",
                                    FailureKind.AccessDenied,
                                )
                            else AppResult.Success(CompanyDataStatus(checkpoint?.refreshedAt))
                        }
                    }
                }
            }
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> observeAdmitted(
        workspaceId: String,
        read: (DocumentAccess) -> Flow<AppResult<T>>,
    ): Flow<AppResult<T>> =
        sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
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
            .flatMapLatest { admitted ->
                when (admitted) {
                    is AppResult.Failed -> flowOf(admitted)
                    is AppResult.Success ->
                        replicas.observeAdmission(admitted.value).flatMapLatest { admission ->
                            when (admission) {
                                is AppResult.Failed -> flowOf(admission)
                                is AppResult.Success -> {
                                    val denial = admission.value.denial
                                    if (denial != null) flowOf(AppResult.Failed(denial))
                                    else read(admitted.value)
                                }
                            }
                        }
                }
            }
            .buffer(0)
}
