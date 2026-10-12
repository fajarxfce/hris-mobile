@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.data.datasources.DocumentContentDataSource
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.DOCUMENT_DOWNLOAD_CHUNK_BYTES
import dev.fajar.starter.documents.domain.policies.DOCUMENT_MAXIMUM_BYTES
import dev.fajar.starter.documents.domain.repositories.DocumentContentRepository
import dev.fajar.starter.network.safeApiCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.annotation.Single

@Single
class RemoteDocumentContentRepository(private val remote: DocumentContentDataSource) :
    DocumentContentRepository {
    override suspend fun inspect(access: DocumentAccess, evidence: DocumentEvidence) = safeApiCall {
        Uuid.parse(access.companyId)
        val validator = documentContentTag(evidence)
        val response = remote.inspect(access.sessionId, access.companyId, evidence.revisionId)
        require(response.entityTags == listOf(validator))
        require(response.lengths == listOf(evidence.size.toString()))
        require(response.mediaTypes.singleOrNull()?.lowercase() == evidence.mediaType)
        require(response.encodings.isEmpty() || response.encodings == listOf("identity"))
    }

    override suspend fun chunk(
        access: DocumentAccess,
        evidence: DocumentEvidence,
        offset: Long,
    ): AppResult<ByteArray> {
        var acquired: ByteArray? = null
        return try {
            val result = safeApiCall {
                Uuid.parse(access.companyId)
                val validator = documentContentTag(evidence)
                require(offset in 0 until evidence.size)
                val bytes =
                    remote.download(
                        access.sessionId,
                        access.companyId,
                        evidence.revisionId,
                        validator,
                        evidence.size,
                        offset,
                        DOCUMENT_DOWNLOAD_CHUNK_BYTES,
                    )
                acquired = bytes
                currentCoroutineContext().ensureActive()
                require(
                    bytes.size.toLong() ==
                        minOf(DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(), evidence.size - offset)
                )
                bytes
            }
            // Only a published success transfers buffer ownership to the caller.
            if (result is AppResult.Success) acquired = null
            result
        } finally {
            acquired?.fill(0)
        }
    }
}

/** Server protocol: the validator binds the immutable revision and its accepted SHA-256. */
private fun documentContentTag(evidence: DocumentEvidence): String {
    require(Uuid.parse(evidence.revisionId).toString() == evidence.revisionId)
    require(
        evidence.size in 1..DOCUMENT_MAXIMUM_BYTES && Regex("[0-9a-f]{64}").matches(evidence.sha256)
    )
    return "\"${evidence.revisionId}-${evidence.sha256}\""
}
