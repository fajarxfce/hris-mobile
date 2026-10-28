package dev.fajar.starter.documents.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.replica.repositories.DocumentCatalogRepository
import dev.fajar.starter.documents.domain.repositories.DocumentFileRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.PendingActionGroup
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveDocumentPending(
    private val files: DocumentFileRepository,
    private val reads: DocumentCatalogRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : ObservePendingActions {
    override val group = PendingActionGroup.Documents

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
            .flatMapLatest { admitted ->
                when (admitted) {
                    is AppResult.Failed -> flowOf(admitted)
                    is AppResult.Success -> {
                        val access = admitted.value
                        reads.observeAdmission(access).flatMapLatest { admission ->
                            when (admission) {
                                is AppResult.Failed -> flowOf(admission)
                                is AppResult.Success -> {
                                    val denial = admission.value.denial
                                    if (denial != null) flowOf(AppResult.Failed(denial))
                                    else
                                        observeStored(access).map { result ->
                                            val latest = reads.admission(access)
                                            currentCoroutineContext().ensureActive()
                                            when (latest) {
                                                is AppResult.Failed -> latest
                                                is AppResult.Success ->
                                                    latest.value.denial?.let {
                                                        AppResult.Failed(it)
                                                    } ?: result
                                            }
                                        }
                                }
                            }
                        }
                    }
                }
            }
            .buffer(0)
            .distinctUntilChanged()

    private fun observeStored(access: DocumentAccess) =
        files.observe(access).map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success -> {
                    val files = result.value
                    AppResult.Success(
                        PendingActionSummary(
                            drafts =
                                files.count {
                                    it.draft.download == null &&
                                        it.upload == null &&
                                        it.sha256 != null
                                },
                            transfers =
                                files.count {
                                    (it.draft.download != null && it.sha256 == null) ||
                                        (it.upload != null &&
                                            it.upload.step !is DocumentUploadStep.Finished)
                                },
                            needsReview =
                                files.count {
                                    (it.upload?.step as? DocumentUploadStep.Finished)
                                        ?.revision
                                        ?.status == DocumentRevisionStatus.VALIDATION_FAILED
                                },
                        )
                    )
                }
            }
        }
}
