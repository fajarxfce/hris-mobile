package dev.fajar.starter.fixtures

import dev.fajar.starter.fileio.Sha256Digest
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.serialization.json.*

/** Minimal authenticated upload protocol for the assembled application tests. */
class NativeDocumentUploadsUi {
    @Volatile var denied = false
    val starts = AtomicInteger()
    val chunks = AtomicInteger()
    val validations = AtomicInteger()
    var submitted: JsonObject? = null
        private set

    private val uploads = mutableMapOf<String, Upload>()
    private val receipts = mutableMapOf<String, Pair<String, String>>()

    fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val endpoint = request.url.encodedPath.substringAfter("/documents/", "")
        if (endpoint != "uploads" && !endpoint.startsWith("revisions/")) return null
        check(request.url.encodedPath.contains("/companies/${NativeUiServer.COMPANY_ONE}/"))
        if (denied) return """{"code":"document_access_denied"}""" to HttpStatusCode.Forbidden
        if (request.method == HttpMethod.Get) return revision(endpoint)
        val operation = requireNotNull(request.headers["Idempotency-Key"])
        val body = (request.body as OutgoingContent.ByteArrayContent).bytes()
        val fingerprint =
            endpoint + ":" + request.headers["Upload-Offset"] + ":" + body.contentHashCode()
        receipts[operation]?.let { (original, receipt) ->
            assertEquals(original, fingerprint)
            return receipt to HttpStatusCode.OK
        }
        val upload =
            when {
                endpoint == "uploads" -> {
                    starts.incrementAndGet()
                    val input = Json.parseToJsonElement(body.decodeToString()).jsonObject
                    submitted = input
                    assertEquals(
                        NativePeopleUi.EMPLOYEE,
                        input.getValue("employmentId").jsonPrimitive.content,
                    )
                    val id = input.getValue("revisionId").jsonPrimitive.content
                    check(id !in uploads)
                    Upload(input, Clock.System.now()).also { uploads[id] = it }
                }
                endpoint.endsWith("/chunks") -> {
                    chunks.incrementAndGet()
                    val upload = uploads.getValue(endpoint.split('/')[1])
                    assertEquals(upload.uploadedBytes.toString(), request.headers["Upload-Offset"])
                    val digest = Sha256Digest()
                    digest.update(body)
                    assertEquals(digest.finishHex(), request.headers["Upload-Checksum-Sha256"])
                    upload.uploadedBytes += body.size
                    upload.version++
                    upload
                }
                endpoint.endsWith("/validate") || endpoint.endsWith("/cancel") -> {
                    val upload = uploads.getValue(endpoint.split('/')[1])
                    val input = Json.parseToJsonElement(body.decodeToString()).jsonObject
                    assertEquals(
                        upload.version,
                        input.getValue("expectedVersion").jsonPrimitive.long,
                    )
                    upload.version++
                    if (endpoint.endsWith("/validate")) {
                        validations.incrementAndGet()
                        assertEquals(
                            upload.input.getValue("size").jsonPrimitive.long,
                            upload.uploadedBytes,
                        )
                        upload.status = "READY"
                    } else upload.status = "CANCELLED"
                    upload
                }
                else -> error("Unexpected document endpoint")
            }
        val receipt =
            buildJsonObject {
                    put("id", upload.input.getValue("revisionId"))
                    put("version", upload.version)
                }
                .toString()
        receipts[operation] = fingerprint to receipt
        return receipt to HttpStatusCode.OK
    }

    private fun revision(endpoint: String): Pair<String, HttpStatusCode> {
        val upload =
            uploads[endpoint.substringAfter("revisions/")]
                ?: return """{"code":"document_revision_not_found"}""" to HttpStatusCode.NotFound
        return buildJsonObject {
                put("id", upload.input.getValue("revisionId"))
                for (key in
                    listOf("documentId", "fileName", "mediaType", "size", "sha256", "reason")) put(
                    key,
                    upload.input.getValue(key),
                )
                put("number", 1)
                put("status", upload.status)
                put("uploadedBytes", upload.uploadedBytes)
                put("chunkSize", 1_048_576)
                put("version", upload.version + if (upload.status == "READY") 1 else 0)
                put("createdBy", NativePeopleUi.ACCOUNT)
                put("createdAt", upload.createdAt.toString())
                put("expiresAt", (upload.createdAt + 24.hours).toString())
                put("validationAttempts", if (upload.status == "READY") 1 else 0)
                put(
                    "validatedAt",
                    if (upload.status == "READY") JsonPrimitive(Clock.System.now().toString())
                    else JsonNull,
                )
            }
            .toString() to HttpStatusCode.OK
    }

    private data class Upload(
        val input: JsonObject,
        val createdAt: Instant,
        var uploadedBytes: Long = 0,
        var version: Long = 0,
        var status: String = "UPLOADING",
    )
}
