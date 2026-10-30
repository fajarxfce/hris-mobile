package dev.fajar.starter.documents.data.metadata.encoding

import dev.fajar.starter.database.ReplicaScope
import dev.fajar.starter.documents.data.metadata.mappers.requireDocumentMetadataId
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import kotlinx.serialization.json.Json

fun documentMetadataScope(access: DocumentAccess) =
    ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun documentMetadataPartition(access: DocumentAccess, id: String): String {
    requireDocumentMetadataId(access.companyId)
    requireDocumentMetadataId(id)
    return "document-metadata/${access.companyId}/$id"
}

fun documentMetadataAssociatedData(
    access: DocumentAccess,
    documentId: String,
    generation: String,
    purpose: String,
    version: Long,
) =
    Json.encodeToString(
            listOf(
                "hris-document-metadata-v1",
                access.audience,
                access.accountId,
                access.companyId,
                documentId,
                generation,
                purpose,
                version.toString(),
            )
        )
        .encodeToByteArray()

fun documentMetadataHistoryKey(version: Long): String {
    require(version in 0..999)
    return "revision/" + version.toString().padStart(3, '0')
}
