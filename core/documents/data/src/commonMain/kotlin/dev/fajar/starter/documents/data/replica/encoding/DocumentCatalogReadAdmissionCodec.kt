@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.replica.encoding

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.documents.data.replica.dto.DocumentCatalogReadAdmissionDto
import dev.fajar.starter.documents.data.replica.mappers.documentCatalogReplicaAssociatedData
import dev.fajar.starter.documents.data.replica.mappers.documentCatalogReplicaScope
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogReadAdmission
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun documentCatalogAdmissionPartition(access: DocumentAccess) =
    "document-catalog-access/" + access.companyId

fun documentCatalogAdmissionBufferKey(access: DocumentAccess) =
    Json.encodeToString(listOf(access.audience, access.accountId, access.companyId))

fun DocumentCatalogReadAdmissionDto.toReadAdmission(): DocumentCatalogReadAdmission {
    Uuid.parse(revision)
    require(code == null || code.length in 1..128)
    return DocumentCatalogReadAdmission(
        revision,
        Failure(FailureKind.valueOf(kind), "Document access requires verification.", code = code),
    )
}

suspend fun encodeDocumentCatalogReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: DocumentAccess,
    dto: DocumentCatalogReadAdmissionDto,
): String {
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                documentCatalogReplicaAssociatedData(access, dto.revision, "read-admission", 0),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeDocumentCatalogReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: DocumentAccess,
    raw: ReplicaCheckpoint,
): DocumentCatalogReadAdmissionDto {
    require(
        raw.handle.scope == documentCatalogReplicaScope(access) &&
            raw.handle.partition == documentCatalogAdmissionPartition(access)
    )
    require(raw.revision == 0L)
    val bytes =
        cipher.decrypt(
            key,
            documentCatalogReplicaAssociatedData(
                access,
                raw.handle.generation,
                "read-admission",
                0,
            ),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<DocumentCatalogReadAdmissionDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .also {
                require(it.revision == raw.handle.generation)
                it.toReadAdmission()
            }
    } finally {
        bytes.fill(0)
    }
}
