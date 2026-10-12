package dev.fajar.starter.transfers.data.api

import dev.fajar.starter.network.*
import dev.fajar.starter.transfers.data.dto.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/** First-party endpoints only. Authentication is scoped by the existing HTTP plugin. */
@Single
class TransferApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun describe(sessionId: String, id: String): RemoteFileDto =
        client.requestJson("files/${transferResourceSegment(id)}") { forSession(sessionId) }

    suspend fun createUpload(sessionId: String, key: String, file: FileMetadataDto): UploadDto =
        client.requestJson("files/uploads", HttpMethod.Post) {
            forSession(sessionId)
            header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody(file)
        }

    suspend fun uploadOffset(sessionId: String, id: String): UploadOffsetDto =
        client
            .prepareHead("files/uploads/${transferResourceSegment(id)}") {
                forSession(sessionId)
                expectSuccess = false
            }
            .execute { response ->
                requireTransferStatus(response, HttpStatusCode.NoContent, HttpStatusCode.OK)
                UploadOffsetDto(
                    requireNotNull(response.headers["Upload-Offset"]?.toLongOrNull()),
                    requireNotNull(response.headers["Upload-Length"]?.toLongOrNull()),
                )
            }

    suspend fun upload(sessionId: String, id: String, offset: Long, bytes: ByteArray): Long =
        client
            .preparePatch("files/uploads/${transferResourceSegment(id)}") {
                forSession(sessionId)
                expectSuccess = false
                header("Upload-Offset", offset)
                header("Tus-Resumable", "1.0.0")
                contentType(ContentType.parse("application/offset+octet-stream"))
                setBody(bytes)
                // PATCH is reconciled through HEAD on the next run, never blindly replayed on 401.
            }
            .execute { response ->
                requireTransferStatus(response, HttpStatusCode.NoContent)
                requireNotNull(response.headers["Upload-Offset"]?.toLongOrNull())
            }

    suspend fun download(
        sessionId: String,
        id: String,
        version: String,
        offset: Long,
        size: Long,
        maxBytes: Int,
    ): ByteArray =
        client.requestBinaryRange(
            "files/${transferResourceSegment(id)}/content",
            version,
            offset,
            size,
            maxBytes,
        ) {
            forSession(sessionId)
            header(HttpHeaders.IfMatch, version)
        }
}
