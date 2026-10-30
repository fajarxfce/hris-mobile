package dev.fajar.starter.documents.data.metadata.encoding

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.documents.data.metadata.dto.DocumentMetadataAdmissionDto
import dev.fajar.starter.documents.data.metadata.mappers.requireDocumentMetadataId
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataReadAdmission
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

fun metadataAdmissionPartition(access: DocumentAccess, id: String): String {
    requireDocumentMetadataId(access.companyId)
    requireDocumentMetadataId(id)
    return "document-metadata-access/${access.companyId}/$id"
}

fun metadataAdmissionBufferKey(access: DocumentAccess, id: String) =
    Json.encodeToString(listOf(access.audience, access.accountId, access.companyId, id))

fun DocumentMetadataAdmissionDto.toMetadataAdmission(): DocumentMetadataReadAdmission {
    requireDocumentMetadataId(revision)
    require(
        code == null || (code.length in 1..128 && code.all { it.isLetterOrDigit() || it in "_.:-" })
    )
    return DocumentMetadataReadAdmission(
        revision,
        Failure(FailureKind.valueOf(kind), "Document access requires verification.", code = code),
    )
}

suspend fun sealMetadataAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: DocumentAccess,
    id: String,
    dto: DocumentMetadataAdmissionDto,
): String {
    dto.toMetadataAdmission()
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    return try {
        require(bytes.size <= 2048)
        Base64.encode(
                cipher.encrypt(
                    key,
                    documentMetadataAssociatedData(access, id, dto.revision, "read-admission", 0),
                    bytes,
                )
            )
            .also { currentCoroutineContext().ensureActive() }
    } finally {
        bytes.fill(0)
    }
}

suspend fun openMetadataAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: DocumentAccess,
    id: String,
    raw: ReplicaCheckpoint,
): DocumentMetadataReadAdmission {
    require(
        raw.handle.scope == documentMetadataScope(access) &&
            raw.handle.partition == metadataAdmissionPartition(access, id)
    )
    require(raw.published && raw.revision == 0L && raw.payload.length <= 4096)
    val bytes =
        cipher.decrypt(
            key,
            documentMetadataAssociatedData(access, id, raw.handle.generation, "read-admission", 0),
            Base64.decode(raw.payload),
        )
    return try {
        currentCoroutineContext().ensureActive()
        require(bytes.size <= 2048)
        val dto =
            Json.decodeFromString<DocumentMetadataAdmissionDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
        require(dto.revision == raw.handle.generation)
        dto.toMetadataAdmission()
    } finally {
        bytes.fill(0)
    }
}
