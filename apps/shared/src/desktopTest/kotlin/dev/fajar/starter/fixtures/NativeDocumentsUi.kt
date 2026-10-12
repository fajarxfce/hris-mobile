package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*

/** Scoped metadata fixture with a cancellable pending acquisition. It never serves file bytes. */
class NativeDocumentsUi {
    @Volatile var enabled = false
    @Volatile var gate: CompletableDeferred<Unit>? = null
    val reads = AtomicInteger()
    val active = AtomicInteger()

    suspend fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        if (!request.url.encodedPath.endsWith("/documents/me/evidence")) return null
        check(enabled)
        check(request.url.encodedPath.contains("/companies/${NativeUiServer.COMPANY_ONE}/"))
        check(request.url.parameters["employmentId"] == NativePeopleUi.EMPLOYEE)
        check(request.url.parameters["classification"] == "PERSONAL")
        check(request.url.parameters["limit"] == "20")
        reads.incrementAndGet()
        active.incrementAndGet()
        try {
            gate?.await()
            return buildJsonObject {
                    put("accountId", NativePeopleUi.ACCOUNT)
                    put("companyId", NativeUiServer.COMPANY_ONE)
                    put("employmentId", NativePeopleUi.EMPLOYEE)
                    put("classification", "PERSONAL")
                    putJsonObject("documents") {
                        putJsonArray("items") {
                            addJsonObject {
                                attachment().forEach { (key, value) -> put(key, value) }
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
