@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.encoding

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.BlobChunkRecord
import dev.fajar.starter.database.BlobRecord
import dev.fajar.starter.documents.data.dto.*
import dev.fajar.starter.documents.data.mappers.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.resolveDocumentFile
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

/** Authenticated storage encoding only; the repository owns key/resource lifetime. */
class DocumentFileCodec(private val cipher: ContentCipher) {
    suspend fun declaration(
        access: DocumentAccess,
        draft: DocumentFileDraft,
        key: ByteArray,
    ): String {
        val dto =
            DocumentFileDto(
                audience = access.audience,
                accountId = access.accountId,
                companyId = access.companyId,
                id = draft.id,
                name = draft.declaration.name,
                mediaType = draft.declaration.mediaType,
                size = draft.declaration.size,
                createdAt = draft.createdAt.toString(),
                download = draft.download?.toDto(),
            )
        return seal(
            access,
            draft.id,
            draft.declaration.size,
            "declaration",
            0,
            key,
            Json.encodeToString(dto).encodeToByteArray(),
        )
    }

    suspend fun block(
        access: DocumentAccess,
        draft: DocumentFileDraft,
        offset: Long,
        bytes: ByteArray,
        key: ByteArray,
    ): String = seal(access, draft.id, draft.declaration.size, "block", offset, key, bytes)

    suspend fun completion(
        access: DocumentAccess,
        draft: DocumentFileDraft,
        sha256: String,
        key: ByteArray,
    ): String =
        seal(
            access,
            draft.id,
            draft.declaration.size,
            "completion",
            0,
            key,
            Json.encodeToString(
                    DocumentFileProofDto(size = draft.declaration.size, sha256 = sha256)
                )
                .encodeToByteArray(),
        )

    suspend fun upload(
        access: DocumentAccess,
        file: LocalDocumentFile,
        upload: DocumentUpload,
        key: ByteArray,
    ): String {
        val dto = upload.toDto()
        dto.toUpload(file.draft.declaration.size)
        return seal(
            access,
            file.draft.id,
            file.draft.declaration.size,
            "upload",
            file.revision + 1,
            key,
            Json.encodeToString(dto).encodeToByteArray(),
        )
    }

    suspend fun readBlock(
        access: DocumentAccess,
        draft: DocumentFileDraft,
        block: BlobChunkRecord,
        key: ByteArray,
    ): ByteArray {
        val bytes =
            cipher.decrypt(
                key,
                associatedData(access, draft.id, draft.declaration.size, "block", block.offset),
                Base64.decode(block.payload),
            )
        if (bytes.size != block.length) {
            bytes.fill(0)
            error("Document block extent changed.")
        }
        return bytes
    }

    suspend fun decode(access: DocumentAccess, raw: BlobRecord, key: ByteArray): LocalDocumentFile {
        require(raw.partition == documentFilePartition(access))
        require(Uuid.parse(raw.id).toString() == raw.id)
        val metadata =
            cipher.decrypt(
                key,
                associatedData(access, raw.id, raw.size, "declaration", 0),
                Base64.decode(raw.metadata),
            )
        val dto =
            try {
                Json.decodeFromString<DocumentFileDto>(
                    metadata.decodeToString(throwOnInvalidSequence = true)
                )
            } finally {
                metadata.fill(0)
            }
        require(
            dto.version == 1 &&
                dto.audience == access.audience &&
                dto.accountId == access.accountId &&
                dto.companyId == access.companyId
        )
        require(dto.id == raw.id && dto.size == raw.size && dto.createdAt.length <= 40)
        val declaration = DocumentFileDeclaration(dto.name, dto.mediaType, dto.size)
        require(
            resolveDocumentFile(DocumentFileSelection(dto.name, dto.mediaType, dto.size)) ==
                AppResult.Success(declaration)
        )
        require(raw.storedBytes in 0..raw.size && raw.revision >= 0)
        val download = dto.download?.toDownloadSource()
        if (download != null) {
            require(raw.state == null)
            require(
                declaration ==
                    DocumentFileDeclaration(
                        download.evidence.fileName,
                        download.evidence.mediaType,
                        download.evidence.size,
                    )
            )
        }
        val sha256 =
            raw.completion?.let { encrypted ->
                require(raw.storedBytes == raw.size)
                val bytes =
                    cipher.decrypt(
                        key,
                        associatedData(access, raw.id, raw.size, "completion", 0),
                        Base64.decode(encrypted),
                    )
                val proof =
                    try {
                        Json.decodeFromString<DocumentFileProofDto>(
                            bytes.decodeToString(throwOnInvalidSequence = true)
                        )
                    } finally {
                        bytes.fill(0)
                    }
                require(
                    proof.version == 1 &&
                        proof.size == raw.size &&
                        Regex("[0-9a-f]{64}").matches(proof.sha256)
                )
                proof.sha256
            }
        require(download == null || sha256 == null || sha256 == download.evidence.sha256)
        val upload =
            raw.state?.let {
                require(sha256 != null)
                val bytes =
                    cipher.decrypt(
                        key,
                        associatedData(access, raw.id, raw.size, "upload", raw.revision),
                        Base64.decode(it),
                    )
                try {
                    Json.decodeFromString<DocumentUploadDto>(
                            bytes.decodeToString(throwOnInvalidSequence = true)
                        )
                        .toUpload(raw.size)
                } finally {
                    bytes.fill(0)
                }
            }
        return LocalDocumentFile(
            DocumentFileDraft(dto.id, declaration, Instant.parse(dto.createdAt), download),
            raw.storedBytes,
            sha256,
            raw.revision,
            upload,
        )
    }

    private suspend fun seal(
        access: DocumentAccess,
        id: String,
        size: Long,
        part: String,
        offset: Long,
        key: ByteArray,
        bytes: ByteArray,
    ): String =
        try {
            Base64.encode(
                cipher.encrypt(key, associatedData(access, id, size, part, offset), bytes)
            )
        } finally {
            bytes.fill(0)
        }

    private fun associatedData(
        access: DocumentAccess,
        id: String,
        size: Long,
        part: String,
        offset: Long,
    ): ByteArray =
        Json.encodeToString(
                listOf(
                    "hris-document-file-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    id,
                    size.toString(),
                    part,
                    offset.toString(),
                )
            )
            .encodeToByteArray()
}

fun documentFilePartition(access: DocumentAccess): String = "documents:${access.companyId}"
