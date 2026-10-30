package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*

class NativeDocumentMetadataUi {
    @Volatile var enabled = false
    @Volatile var offline = false
    @Volatile var denial: String? = null
    @Volatile var gate: CompletableDeferred<Unit>? = null
    val active = AtomicInteger()
    val headReads = AtomicInteger()
    val historyReads = AtomicInteger()
    val latest = 21L
    private val recordedAt = Clock.System.now().toString()

    fun value(version: Long) = buildJsonObject {
        put("category", "PASSPORT")
        put("identityNumber", "PASSPORT-$version")
        put("issuingCountry", "AU")
        put("issuedOn", "2010-01-01")
        put("validUntil", "2020-01-01")
    }

    suspend fun response(request: HttpRequestData, title: String): Pair<String, HttpStatusCode>? {
        if (!enabled) return null
        val path = request.url.encodedPath
        val base =
            "/companies/${NativeUiServer.COMPANY_ONE}/documents/${NativeDocumentsUi.DOCUMENT}"
        val history = path.endsWith("$base/metadata-history")
        if (!history && !path.endsWith(base)) return null
        check(request.method == HttpMethod.Get)
        if (history) historyReads.incrementAndGet() else headReads.incrementAndGet()
        active.incrementAndGet()
        try {
            gate?.await()
            if (offline) throw java.io.IOException("Simulated metadata connection loss")
            denial?.let {
                return """{"code":"$it"}""" to HttpStatusCode.Forbidden
            }
            if (history) return history(request).toString() to HttpStatusCode.OK
            return buildJsonObject {
                    put("id", NativeDocumentsUi.DOCUMENT)
                    put("employmentId", NativePeopleUi.EMPLOYEE)
                    put("title", title)
                    put("classification", "PERSONAL")
                    put("revisionCount", 1)
                    put("version", latest + 1)
                    put("createdBy", NativePeopleUi.ACCOUNT)
                    put("createdAt", recordedAt)
                    put("currentRevisionId", NativeDocumentsUi.REVISION)
                    put("metadata", value(latest))
                    put("metadataVersion", latest)
                    put("metadataSupported", true)
                }
                .toString() to HttpStatusCode.OK
        } finally {
            active.decrementAndGet()
        }
    }

    private fun history(request: HttpRequestData) = buildJsonObject {
        check(request.url.parameters["limit"] == "20")
        val after = request.url.parameters["after"]?.toLong() ?: -1L
        val end = minOf(after + 20, latest)
        putJsonArray("items") {
            for (version in after + 1..end) addJsonObject {
                put("documentId", NativeDocumentsUi.DOCUMENT)
                put("version", version)
                put("documentVersion", version + 1)
                put("metadata", value(version))
                put(
                    "actorId",
                    if (version == 0L) JsonNull else JsonPrimitive(NativePeopleUi.ACCOUNT),
                )
                put("recordedAt", recordedAt)
                put(
                    "reason",
                    if (version == 0L) JsonNull else JsonPrimitive("Printed correction $version"),
                )
            }
        }
        put("nextCursor", if (end < latest) JsonPrimitive(end.toString()) else JsonNull)
    }
}
