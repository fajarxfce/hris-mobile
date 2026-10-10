package dev.fajar.starter.transfers.data.api

import dev.fajar.starter.network.*
import dev.fajar.starter.transfers.data.dto.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/** First-party endpoints only. Authentication is scoped by the existing HTTP plugin. */
@Single
class TransferApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun describe(sessionId: String, id: String): RemoteFileDto =
        client.get("files/${transferResourceSegment(id)}") { forSession(sessionId) }.body()

    suspend fun createUpload(sessionId: String, key: String, file: FileMetadataDto): UploadDto =
        client
            .post("files/uploads") {
                forSession(sessionId)
                header("Idempotency-Key", key)
                contentType(ContentType.Application.Json)
                setBody(file)
            }
            .body()

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
    ): ByteArray {
        val end = minOf(size, offset + maxBytes) - 1
        require(offset >= 0 && end >= offset)
        return client
            .prepareGet("files/${transferResourceSegment(id)}/content") {
                forSession(sessionId)
                expectSuccess = false
                header(HttpHeaders.Range, "bytes=$offset-$end")
                header(HttpHeaders.IfMatch, version)
                header(HttpHeaders.AcceptEncoding, "identity")
            }
            .execute { response ->
                requireTransferStatus(response, HttpStatusCode.PartialContent)
                require(response.headers[HttpHeaders.ETag] == version)
                require(response.headers[HttpHeaders.ContentRange] == "bytes $offset-$end/$size")
                require(
                    response.headers[HttpHeaders.ContentEncoding].let {
                        it == null || it == "identity"
                    }
                )
                val expected = (end - offset + 1).toInt()
                require(
                    response.headers[HttpHeaders.ContentLength]?.toLongOrNull().let {
                        it == null || it == expected.toLong()
                    }
                )
                // Read one extra byte to detect an oversized response without buffering an
                // unbounded body.
                val bytes =
                    response.bodyAsChannel().readRemaining(expected.toLong() + 1).readByteArray()
                require(bytes.size == expected)
                bytes
            }
    }
}
