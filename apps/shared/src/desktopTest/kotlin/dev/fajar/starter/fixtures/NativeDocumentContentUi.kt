package dev.fajar.starter.fixtures

import dev.fajar.starter.fileio.Sha256Digest
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*

/** Exact authenticated range responses for the assembled download/export workflow. */
class NativeDocumentContentUi {
    @Volatile var enabled = false
    @Volatile var denied = false
    @Volatile var offline = false
    @Volatile var continuation: CompletableDeferred<Unit>? = null
    val heads = AtomicInteger()
    val active = AtomicInteger()
    val offsets = CopyOnWriteArrayList<Long>()
    val bytes = ByteArray(2 * 1_048_576 + 3) { (it % 253).toByte() }
    val sha256 = Sha256Digest().apply { update(bytes) }.finishHex()
    val tag = "\"${NativeDocumentsUi.REVISION}-$sha256\""

    fun attachment() = buildJsonObject {
        put("documentId", NativeDocumentsUi.DOCUMENT)
        put("revisionId", NativeDocumentsUi.REVISION)
        put("fileName", "certificate.pdf")
        put("mediaType", "application/pdf")
        put("size", bytes.size)
        put("sha256", sha256)
    }

    suspend fun response(
        request: HttpRequestData,
        scope: MockRequestHandleScope,
    ): HttpResponseData? {
        if (
            !request.url.encodedPath.endsWith(
                "/documents/revisions/${NativeDocumentsUi.REVISION}/content"
            )
        )
            return null
        check(enabled)
        assertTrue(request.url.encodedPath.contains("/companies/${NativeUiServer.COMPANY_ONE}/"))
        if (offline) throw IOException("Test transport unavailable")
        if (denied)
            return scope.respond(
                """{"code":"document_access_denied"}""",
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        val headers =
            HeadersBuilder().apply {
                append(HttpHeaders.ETag, tag)
                append(HttpHeaders.ContentType, "application/pdf")
            }
        if (request.method == HttpMethod.Head) {
            heads.incrementAndGet()
            headers.append(HttpHeaders.ContentLength, bytes.size.toString())
            return scope.respond(byteArrayOf(), HttpStatusCode.OK, headers.build())
        }
        assertEquals(HttpMethod.Get, request.method)
        assertEquals(tag, request.headers[HttpHeaders.IfRange])
        val bounds =
            Regex("bytes=(\\d+)-(\\d+)")
                .matchEntire(requireNotNull(request.headers[HttpHeaders.Range]))!!
                .destructured
        val start = bounds.component1().toInt()
        val end = bounds.component2().toInt()
        assertTrue(end - start + 1 in 1..1_048_576 && end < bytes.size)
        offsets += start.toLong()
        active.incrementAndGet()
        try {
            if (start > 0) continuation?.await()
            headers.append(HttpHeaders.ContentLength, (end - start + 1).toString())
            headers.append(HttpHeaders.ContentRange, "bytes $start-$end/${bytes.size}")
            return scope.respond(
                bytes.copyOfRange(start, end + 1),
                HttpStatusCode.PartialContent,
                headers.build(),
            )
        } finally {
            active.decrementAndGet()
        }
    }
}
