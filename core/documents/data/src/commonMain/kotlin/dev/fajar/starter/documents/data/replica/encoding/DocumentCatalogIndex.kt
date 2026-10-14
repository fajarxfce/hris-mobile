package dev.fajar.starter.documents.data.replica.encoding

import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.securestorage.contentIndexes
import kotlinx.serialization.json.Json

/** Ownership and category are authenticated index inputs, never plaintext search columns. */
suspend fun documentCatalogIndex(
    key: ByteArray,
    access: DocumentAccess,
    employmentId: String,
    classification: DocumentClassification,
): String {
    val bytes =
        Json.encodeToString(
                listOf(
                    "hris-document-catalog-index-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    employmentId,
                    classification.name,
                )
            )
            .encodeToByteArray()
    return try {
        contentIndexes(key, listOf(bytes)).single()
    } finally {
        bytes.fill(0)
    }
}
