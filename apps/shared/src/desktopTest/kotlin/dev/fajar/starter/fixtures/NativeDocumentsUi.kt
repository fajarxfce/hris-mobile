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
    @Volatile var metadataSupported = false
    @Volatile var gate: CompletableDeferred<Unit>? = null
    @Volatile var catalogOffline = false
    @Volatile var catalogDenied = false
    @Volatile var catalogRemoved = false
    @Volatile var catalogVersion = 0L
    @Volatile var catalogTitle = "Medical certificate"
    val catalogReads = AtomicInteger()
    private val validatedAt = Clock.System.now().toString()
    val reads = AtomicInteger()
    val active = AtomicInteger()
    val uploads = NativeDocumentUploadsUi()
    val downloads = NativeDocumentContentUi()
    val metadata = NativeDocumentMetadataUi()

    suspend fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        metadata.response(request, catalogTitle)?.let {
            return it
        }
        catalogResponse(request)?.let {
            return it
        }
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
                        put("metadataSupported", metadataSupported)
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

    private fun catalogResponse(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val catalogVersion =
            if (metadata.enabled) maxOf(this.catalogVersion, metadata.latest + 1)
            else this.catalogVersion
        val path = request.url.encodedPath
        val sync =
            path.contains("/sync/") && request.url.parameters["collections"] == "DOCUMENT_EVIDENCE"
        if (!sync && !path.contains("/documents/me/evidence/")) return null
        check(enabled && path.contains("/companies/${NativeUiServer.COMPANY_ONE}/"))
        catalogReads.incrementAndGet()
        if (catalogOffline) throw java.io.IOException("Simulated offline catalog")
        if (catalogDenied) return """{"code":"mfa_required"}""" to HttpStatusCode.Forbidden
        if (!sync) {
            check(path.substringAfterLast('/') == DOCUMENT)
            return buildJsonObject {
                    put("id", DOCUMENT)
                    put("version", catalogVersion)
                    put("employmentId", NativePeopleUi.EMPLOYEE)
                    put("classification", "PERSONAL")
                    put("metadataSupported", metadataSupported)
                    if (metadataSupported && metadata.enabled) {
                        put("metadataVersion", metadata.latest)
                        put("metadata", metadata.value(metadata.latest))
                    } else if (metadataSupported) {
                        put("metadataVersion", 0)
                        putJsonObject("metadata") {
                            put("category", "CERTIFICATE")
                            for (field in
                                listOf(
                                    "identityNumber",
                                    "issuingCountry",
                                    "issuedOn",
                                    "validUntil",
                                )) put(field, JsonNull)
                        }
                    }
                    putJsonObject("evidence") {
                        (if (downloads.enabled) downloads.attachment() else attachment()).forEach {
                            (key, value) ->
                            put(key, value)
                        }
                        put("revisionNumber", 1)
                        put("title", catalogTitle)
                        put("validatedAt", validatedAt)
                    }
                }
                .toString() to HttpStatusCode.OK
        }
        check(request.url.parameters["limit"] == "50")
        val bootstrap = path.endsWith("/bootstrap")
        return buildJsonObject {
                if (bootstrap) putJsonArray("collections") { add("DOCUMENT_EVIDENCE") }
                putJsonArray("items") {
                    if ((bootstrap && !catalogRemoved) || (!bootstrap && catalogVersion > 0))
                        addJsonObject {
                            put("collection", "DOCUMENT_EVIDENCE")
                            if (!bootstrap)
                                put("operation", if (catalogRemoved) "DELETE" else "UPSERT")
                            put("id", DOCUMENT)
                            put("version", catalogVersion)
                        }
                }
                if (bootstrap) {
                    put("nextCursor", JsonNull)
                    put("changesCursor", "document-catalog-head-$catalogVersion")
                } else {
                    put("cursor", "document-catalog-head-$catalogVersion")
                    put("hasMore", false)
                    put("pendingPublication", false)
                    put("pollAfterSeconds", 5)
                }
                put("serverTime", validatedAt)
            }
            .toString() to HttpStatusCode.OK
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
