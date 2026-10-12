package dev.fajar.starter.documents.data.datasources

import dev.fajar.starter.documents.data.dto.DocumentContentDto

interface DocumentContentDataSource {
    suspend fun inspect(
        sessionId: String,
        companyId: String,
        revisionId: String,
    ): DocumentContentDto

    suspend fun download(
        sessionId: String,
        companyId: String,
        revisionId: String,
        entityTag: String,
        size: Long,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray
}
