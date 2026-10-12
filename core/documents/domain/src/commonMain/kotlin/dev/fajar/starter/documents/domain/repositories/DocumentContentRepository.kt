package dev.fajar.starter.documents.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.entities.DocumentEvidence

interface DocumentContentRepository {
    /** Revalidate current content access and the selected immutable revision's declaration. */
    suspend fun inspect(access: DocumentAccess, evidence: DocumentEvidence): AppResult<Unit>

    /** The caller owns and clears the returned bounded buffer, including on cancellation. */
    suspend fun chunk(
        access: DocumentAccess,
        evidence: DocumentEvidence,
        offset: Long,
    ): AppResult<ByteArray>
}
