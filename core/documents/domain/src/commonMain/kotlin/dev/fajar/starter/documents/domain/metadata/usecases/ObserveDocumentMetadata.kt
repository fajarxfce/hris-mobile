@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.metadata.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataView
import dev.fajar.starter.documents.domain.metadata.repositories.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.replica.repositories.DocumentCatalogRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveDocumentMetadata(
    private val cache: DocumentMetadataCacheRepository,
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
        documentId: String,
        after: Flow<Long?>,
    ): Flow<AppResult<DocumentMetadataView>> {
        if (Uuid.parseOrNull(documentId)?.toString() != documentId)
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
                    is AppResult.Success -> observeAdmitted(access.value, documentId, after)
                }
            }
            .buffer(0)
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAdmitted(
        access: DocumentAccess,
        documentId: String,
        after: Flow<Long?>,
    ): Flow<AppResult<DocumentMetadataView>> =
        catalog.observeAdmission(access).flatMapLatest { module ->
            val moduleDenial = (module as? AppResult.Success)?.value?.denial
            when {
                module is AppResult.Failed -> flowOf(module)
                moduleDenial != null -> flowOf(AppResult.Failed(moduleDenial))
                else ->
                    admission.observe(access, documentId).flatMapLatest { resource ->
                        val resourceDenial = (resource as? AppResult.Success)?.value?.denial
                        when {
                            resource is AppResult.Failed -> flowOf(resource)
                            resourceDenial != null -> flowOf(AppResult.Failed(resourceDenial))
                            else ->
                                after.distinctUntilChanged().flatMapLatest { selected ->
                                    if (selected != null && selected !in 0..999)
                                        flowOf(documentFailure("client.document_metadata_invalid"))
                                    else
                                        cache.observe(access, documentId, selected).map { result ->
                                            currentCoroutineContext().ensureActive()
                                            when (result) {
                                                is AppResult.Failed -> result
                                                is AppResult.Success -> {
                                                    val head = result.value.checkpoint?.head
                                                    if (head != null && head.id != documentId)
                                                        documentFailure(
                                                            "client.document_scope_changed",
                                                            FailureKind.AccessDenied,
                                                        )
                                                    else
                                                        AppResult.Success(
                                                            DocumentMetadataView(
                                                                access,
                                                                result.value,
                                                            )
                                                        )
                                                }
                                            }
                                        }
                                }
                        }
                    }
            }
        }
}
