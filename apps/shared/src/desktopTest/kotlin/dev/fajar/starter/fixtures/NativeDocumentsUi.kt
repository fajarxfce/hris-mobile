package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*

/** Scoped metadata and upload fixtures, with cancellable reference acquisition. */
class NativeDocumentsUi {
    @Volatile var enabled = false
    @Volatile var uploadEnabled = false
    @Volatile var gate: CompletableDeferred<Unit>? = null
    val reads = AtomicInteger()
    val active = AtomicInteger()
    val uploads = NativeDocumentUploadsUi()
    val downloads = NativeDocumentContentUi()

    suspend fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val endpoint = request.url.encodedPath.substringAfter("/documents/", "")
        if (endpoint == "uploads" || endpoint.startsWith("revisions/"))
            check(enabled && uploadEnabled)
        uploads.response(request)?.let {
            return it
        }
        val directory = request.url.encodedPath.endsWith("/documents/me/employments")
        if (!directory && !request.url.encodedPath.endsWith("/documents/me/evidence")) return null
        check(enabled)
        check(request.url.encodedPath.contains("/companies/${NativeUiServer.COMPANY_ONE}/"))
        if (!directory) {
            check(request.url.parameters["employmentId"] == NativePeopleUi.EMPLOYEE)
            check(
                request.url.parameters["classification"] in
                    if (downloads.enabled) setOf("PERSONAL", "RECEIPT") else setOf("PERSONAL")
            )
        }
        check(request.url.parameters["limit"] == "20")
        reads.incrementAndGet()
        active.incrementAndGet()
        try {
            gate?.await()
            if (directory)
                return buildJsonObject {
                        put("accountId", NativePeopleUi.ACCOUNT)
                        put("companyId", NativeUiServer.COMPANY_ONE)
                        put("asOf", "2026-10-12")
                        put("timezone", "Asia/Jakarta")
                        putJsonObject("employments") {
                            putJsonArray("items") {
                                addJsonObject {
                                    put("id", NativePeopleUi.EMPLOYEE)
                                    put("employeeNumber", "EMP-001")
                                    put("status", "ACTIVE")
                                    put("version", 0)
                                }
                            }
                            put("nextCursor", JsonNull)
                        }
                    }
                    .toString() to HttpStatusCode.OK
            return buildJsonObject {
                    put("accountId", NativePeopleUi.ACCOUNT)
                    put("companyId", NativeUiServer.COMPANY_ONE)
                    put("employmentId", NativePeopleUi.EMPLOYEE)
                    put("classification", request.url.parameters["classification"])
                    putJsonObject("documents") {
                        putJsonArray("items") {
                            if (request.url.parameters["classification"] == "PERSONAL")
                                addJsonObject {
                                    (if (downloads.enabled) downloads.attachment()
                                        else attachment())
                                        .forEach { (key, value) -> put(key, value) }
                                    put("revisionNumber", 1)
                                    put("title", "Medical certificate")
                                    put("validatedAt", Clock.System.now().toString())
                                }
                        }
                        put("nextCursor", JsonNull)
                    }
                }
                .toString() to HttpStatusCode.OK
        } finally {
            active.decrementAndGet()
        }
    }

    companion object {
        const val DOCUMENT = "12345678-1234-4234-8234-123456789abc"
        const val REVISION = "22345678-1234-4234-8234-123456789abc"

        fun attachment() = buildJsonObject {
            put("documentId", DOCUMENT)
            put("revisionId", REVISION)
            put("fileName", "certificate.pdf")
            put("mediaType", "application/pdf")
            put("size", 100)
            put("sha256", "a".repeat(64))
        }
    }
}
