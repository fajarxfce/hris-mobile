@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.metadata.working.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataRestriction
import dev.fajar.starter.documents.domain.metadata.repositories.*
import dev.fajar.starter.documents.domain.metadata.working.entities.*
import dev.fajar.starter.documents.domain.metadata.working.policies.validateDocumentMetadataWorkingCopy
import dev.fajar.starter.documents.domain.metadata.working.repositories.DocumentMetadataWorkingRepository
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.replica.repositories.DocumentCatalogRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveDocumentMetadataWorkingCopies(
    private val copies: DocumentMetadataWorkingRepository,
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
        after: Flow<String?> = flowOf(null),
    ): Flow<AppResult<DocumentMetadataWorkingView>> {
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
                    is AppResult.Success -> observeAdmitted(access.value, documentId, after)
                }
            }
            .buffer(0)
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAdmitted(
        access: DocumentAccess,
        id: String?,
        after: Flow<String?>,
    ): Flow<AppResult<DocumentMetadataWorkingView>> =
        catalog.observeAdmission(access).flatMapLatest { module ->
            val denial = (module as? AppResult.Success)?.value?.denial
            when {
                module is AppResult.Failed -> flowOf(module)
                denial != null -> flowOf(AppResult.Failed(denial))
                id != null -> observeOne(access, id)
                else ->
                    after.distinctUntilChanged().flatMapLatest { cursor ->
                        if (cursor != null && Uuid.parseOrNull(cursor)?.toString() != cursor)
                            flowOf(documentFailure("client.document_working_copy_invalid"))
                        else
                            copies.observePage(access, cursor).flatMapLatest { result ->
                                when (result) {
                                    is AppResult.Failed -> flowOf(result)
                                    is AppResult.Success ->
                                        visiblePage(access, result.value, cursor)
                                }
                            }
                    }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeOne(
        access: DocumentAccess,
        id: String,
    ): Flow<AppResult<DocumentMetadataWorkingView>> =
        admission.observe(access, id).flatMapLatest { gate ->
            val denial = (gate as? AppResult.Success)?.value?.denial
            when {
                gate is AppResult.Failed -> flowOf(gate)
                denial != null ->
                    flowOf(
                        AppResult.Success(
                            DocumentMetadataWorkingView(
                                access,
                                DocumentMetadataWorkingPage(emptyList(), null),
                                listOf(DocumentMetadataRestriction(id, denial)),
                            )
                        )
                    )
                else ->
                    copies.observe(access, id).map { result ->
                        currentCoroutineContext().ensureActive()
                        when (result) {
                            is AppResult.Failed -> result
                            is AppResult.Success -> {
                                val copy = result.value
                                if (
                                    copy != null &&
                                        (copy.head.id != id ||
                                            validateDocumentMetadataWorkingCopy(copy) is
                                                AppResult.Failed)
                                )
                                    documentFailure(
                                        "client.document_scope_changed",
                                        FailureKind.AccessDenied,
                                    )
                                else
                                    AppResult.Success(
                                        DocumentMetadataWorkingView(
                                            access,
                                            DocumentMetadataWorkingPage(listOfNotNull(copy), null),
                                            emptyList(),
                                        )
                                    )
                            }
                        }
                    }
            }
        }

    private fun visiblePage(
        access: DocumentAccess,
        page: DocumentMetadataWorkingPage,
        after: String?,
    ): Flow<AppResult<DocumentMetadataWorkingView>> {
        val ids = page.items.map { it.head.id }
        if (
            page.items.size > 20 ||
                ids != ids.distinct().sorted() ||
                page.items.any { validateDocumentMetadataWorkingCopy(it) is AppResult.Failed } ||
                (after != null && ids.any { it <= after }) ||
                (page.nextDocumentId != null &&
                    (page.items.size != 20 || page.nextDocumentId != ids.lastOrNull()))
        )
            return flowOf(documentFailure("client.document_working_copy_invalid"))
        if (page.items.isEmpty())
            return flowOf(
                AppResult.Success(DocumentMetadataWorkingView(access, page, emptyList(), after))
            )
        return combine(
            page.items.map { copy ->
                admission.observe(access, copy.head.id).map { gate ->
                    when (gate) {
                        is AppResult.Failed ->
                            WorkingVisibility(
                                null,
                                DocumentMetadataRestriction(copy.head.id, gate.failure),
                            )
                        is AppResult.Success ->
                            if (gate.value.denial != null)
                                WorkingVisibility(
                                    null,
                                    DocumentMetadataRestriction(
                                        copy.head.id,
                                        requireNotNull(gate.value.denial),
                                    ),
                                )
                            else WorkingVisibility(copy, null)
                    }
                }
            }
        ) { values ->
            currentCoroutineContext().ensureActive()
            AppResult.Success(
                DocumentMetadataWorkingView(
                    access,
                    page.copy(items = values.mapNotNull { it.copy }),
                    values.mapNotNull { it.restriction },
                    after,
                )
            )
        }
    }
}

private data class WorkingVisibility(
    val copy: DocumentMetadataWorkingCopy?,
    val restriction: DocumentMetadataRestriction?,
)
