@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentEvidenceQuery
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

const val DOCUMENT_MAXIMUM_BYTES = 104_857_600L
const val DOCUMENT_PAGE_SIZE = 20
val DOCUMENT_EVIDENCE_PERIOD = 5.minutes

fun validateDocumentEvidenceQuery(query: DocumentEvidenceQuery): AppResult<Unit> =
    when {
        Uuid.parseOrNull(query.employmentId)?.toString() != query.employmentId ->
            documentFailure("client.document_selection_invalid")
        query.after != null && Uuid.parseOrNull(query.after)?.toString() != query.after ->
            documentFailure("invalid_document_page")
        else -> AppResult.Success(Unit)
    }
