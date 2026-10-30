package dev.fajar.starter.documents.domain.replica.entities

import dev.fajar.starter.documents.domain.entities.*
import kotlin.time.Instant

/** Request metadata owns acquisition; published facts belong to the account and company. */
data class DocumentCatalogCheckpoint(
    val access: DocumentAccess,
    val generation: String,
    val revision: Long,
    val phase: DocumentCatalogSyncPhase,
    val cursor: String?,
    val published: Boolean,
    val refreshedAt: Instant?,
    val refreshAfter: Instant,
    val pollAt: Instant?,
    val employments: List<DocumentEmployment>,
    val recordSchema: Int = CURRENT_RECORD_SCHEMA,
    val metadataSupported: Boolean = false,
) {
    override fun toString() = "DocumentCatalogCheckpoint([redacted])"

    companion object {
        const val CURRENT_RECORD_SCHEMA = 2
    }
}
