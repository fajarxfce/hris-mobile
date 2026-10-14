@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.replica.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.documentFailure
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

val DOCUMENT_CATALOG_REFRESH_PERIOD = 1.hours

fun sameStoredDocumentScope(first: DocumentAccess, second: DocumentAccess) =
    first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId

fun validateDocumentCatalogSelection(selection: DocumentCatalogSelection): AppResult<Unit> =
    when {
        selection.employmentId != null &&
            Uuid.parseOrNull(selection.employmentId)?.toString() != selection.employmentId ->
            documentFailure("client.document_selection_invalid")
        selection.after != null &&
            (selection.employmentId == null ||
                Uuid.parseOrNull(selection.after)?.toString() != selection.after) ->
            documentFailure("invalid_document_page")
        else -> AppResult.Success(Unit)
    }

fun initialDocumentCatalogCheckpoint(
    access: DocumentAccess,
    generation: String,
    now: Instant,
    employments: List<DocumentEmployment>,
) =
    DocumentCatalogCheckpoint(
        access,
        generation,
        0,
        DocumentCatalogSyncPhase.BOOTSTRAP,
        null,
        false,
        null,
        now + DOCUMENT_CATALOG_REFRESH_PERIOD,
        null,
        employments.toList(),
    )

internal fun advanceDocumentCatalogCheckpoint(
    previous: DocumentCatalogCheckpoint,
    page: DocumentCatalogSyncPage,
    now: Instant,
    employments: List<DocumentEmployment>,
): DocumentCatalogCheckpoint {
    val caughtUp =
        page.phase == DocumentCatalogSyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
    return previous.copy(
        revision = previous.revision + 1,
        phase = page.phase,
        cursor = page.cursor,
        published = previous.published || caughtUp,
        refreshedAt = if (caughtUp) now else previous.refreshedAt,
        refreshAfter =
            if (caughtUp) now + DOCUMENT_CATALOG_REFRESH_PERIOD else previous.refreshAfter,
        pollAt = if (page.hasMore || caughtUp) null else now + page.pollAfterSeconds.seconds,
        employments = employments.toList(),
    )
}

fun documentCatalogFailureClosesRead(failure: Failure) =
    failure.kind == FailureKind.AccessDenied ||
        failure.code in workspaceAccessLossCodes ||
        failure.code in
            setOf(
                "sync_scope_changed",
                "client.session_changed",
                "company_maintenance",
                "client_update_required",
                "client_version_required",
            )
