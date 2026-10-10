package dev.fajar.starter.demo

import dev.fajar.starter.transfers.data.dto.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** In-process protocol fixture. Offsets are shared by clients; no full file buffer is retained. */
class DemoTransferServer {
    internal val requests = Mutex()
    internal val uploads = linkedMapOf<String, DemoUpload>()
}

internal data class DemoUpload(
    val owner: String,
    val metadata: FileMetadataDto,
    val offset: Long = 0,
)

internal suspend fun MockRequestHandleScope.respondDemoTransfer(
    request: HttpRequestData,
    server: DemoTransferServer,
) =
    server.requests.withLock {
        val authorization = request.headers[HttpHeaders.Authorization]
        val owner = authorization?.split(":")?.getOrNull(1)
        val path = request.url.segments
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            !validDemoToken(authorization) -> respond("", HttpStatusCode.Unauthorized)
            path == listOf("files", "uploads") && request.method == HttpMethod.Post -> {
                if (owner != "demo-user") return@withLock respond("", HttpStatusCode.Forbidden)
                val key =
                    request.headers["Idempotency-Key"]
                        ?: return@withLock respond("", HttpStatusCode.BadRequest)
                val file =
                    Json.decodeFromString<FileMetadataDto>(
                        request.body.toByteArray().decodeToString()
                    )
                if (file != sampleTransferMetadata)
                    return@withLock respond("", HttpStatusCode.BadRequest)
                val previous = server.uploads[key]
                if (previous != null && (previous.metadata != file || previous.owner != owner))
                    return@withLock respond("", HttpStatusCode.Conflict)
                if (previous == null) {
                    // Fixture metadata has a finite lifetime and capacity, like its in-process
                    // server.
                    if (server.uploads.size >= 64)
                        server.uploads.remove(server.uploads.keys.first())
                    server.uploads[key] = DemoUpload(owner, file)
                }
                respond(Json.encodeToString(UploadDto(key)), headers = jsonHeaders)
            }
            path.size == 3 && path[1] == "uploads" -> {
                if (owner != "demo-user") return@withLock respond("", HttpStatusCode.Forbidden)
                val id = path[2]
                val upload =
                    server.uploads[id]?.takeIf { it.owner == owner }
                        ?: return@withLock respond("", HttpStatusCode.NotFound)
                when (request.method) {
                    HttpMethod.Head ->
                        respond(
                            "",
                            HttpStatusCode.NoContent,
                            headersOf(
                                "Upload-Offset" to listOf(upload.offset.toString()),
                                "Upload-Length" to listOf(upload.metadata.size.toString()),
                            ),
                        )
                    HttpMethod.Patch -> {
                        val offset = request.headers["Upload-Offset"]?.toLongOrNull()
                        if (offset != upload.offset)
                            return@withLock respond("", HttpStatusCode.Conflict)
                        val bytes = request.body.toByteArray()
                        if (
                            bytes.isEmpty() ||
                                bytes.size > 262_144 ||
                                bytes.size > upload.metadata.size - offset ||
                                !bytes.contentEquals(sampleTransferBytes(offset, bytes.size))
                        )
                            return@withLock respond("", HttpStatusCode.BadRequest)
                        val updated = offset + bytes.size
                        server.uploads[id] = upload.copy(offset = updated)
                        respond(
                            "",
                            HttpStatusCode.NoContent,
                            headersOf("Upload-Offset", updated.toString()),
                        )
                    }
                    else -> respond("", HttpStatusCode.MethodNotAllowed)
                }
            }
            path.size in 2..3 -> respondDemoDownload(request, server, owner, path)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

private suspend fun MockRequestHandleScope.respondDemoDownload(
    request: HttpRequestData,
    server: DemoTransferServer,
    owner: String?,
    path: List<String>,
): io.ktor.client.request.HttpResponseData {
    val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    val id = path[1]
    val upload = server.uploads[id]?.takeIf { it.owner == owner && it.offset == it.metadata.size }
    val file =
        if (id == "sample-report") sampleTransferMetadata
        else upload?.metadata ?: return respond("", HttpStatusCode.NotFound)
    val version = "\"sample-v1\""
    return if (path.size == 2 && request.method == HttpMethod.Get)
        respond(Json.encodeToString(RemoteFileDto(id, file, version)), headers = jsonHeaders)
    else if (path.last() == "content" && request.method == HttpMethod.Get) {
        if (request.headers[HttpHeaders.IfMatch] != version)
            return respond("", HttpStatusCode.PreconditionFailed)
        val range =
            Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.headers[HttpHeaders.Range].orEmpty())
                ?: return respond("", HttpStatusCode.BadRequest)
        val start = range.groupValues[1].toLong()
        val end = range.groupValues[2].toLong()
        if (start < 0 || end < start || end >= file.size || end - start + 1 > 262_144)
            return respond("", HttpStatusCode.RequestedRangeNotSatisfiable)
        respond(
            sampleTransferBytes(start, (end - start + 1).toInt()),
            HttpStatusCode.PartialContent,
            headersOf(
                HttpHeaders.ContentRange to listOf("bytes $start-$end/${file.size}"),
                HttpHeaders.ETag to listOf(version),
                HttpHeaders.ContentLength to listOf((end - start + 1).toString()),
            ),
        )
    } else respond("", HttpStatusCode.MethodNotAllowed)
}
