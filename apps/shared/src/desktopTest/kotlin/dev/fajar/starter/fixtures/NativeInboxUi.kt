package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import java.io.IOException
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.json.*

/** Independent wire fixture for the native inbox, with no runtime mock registration. */
class NativeInboxUi {
    @Volatile var offline = false
    @Volatile var archived = false
    val firstId = "10000000-0000-4000-8000-000000000001"
    val firstBody =
        "The office will be closed on Friday. Please check with your manager for coverage."
    private val ids = (1..36).map { "10000000-0000-4000-8000-" + it.toString().padStart(12, '0') }

    fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val path = request.url.encodedPath
        if (!path.contains("/sync/") && !path.contains("/inbox/")) return null
        if (offline) throw IOException("Simulated offline inbox")
        val company = path.split('/').getOrNull(4)
        check(company in setOf(NativeUiServer.COMPANY_ONE, NativeUiServer.COMPANY_TWO))
        check(request.method == HttpMethod.Get)
        val ownCompany = company == NativeUiServer.COMPANY_ONE
        val body =
            when {
                path.endsWith("/sync/bootstrap") -> {
                    assertEquals("INBOX", request.url.parameters["collections"])
                    assertEquals("50", request.url.parameters["limit"])
                    buildJsonObject {
                        putJsonArray("collections") { add("INBOX") }
                        putJsonArray("items") {
                            if (ownCompany)
                                ids.filterNot { archived && it == firstId }
                                    .forEach { id ->
                                        addJsonObject {
                                            put("collection", "INBOX")
                                            put("id", id)
                                            put("version", 1)
                                        }
                                    }
                        }
                        put("nextCursor", JsonNull)
                        put("changesCursor", "fixture-delta")
                        put("serverTime", "2026-10-11T00:00:00Z")
                    }
                }
                path.endsWith("/sync/changes") ->
                    buildJsonObject {
                        assertEquals("INBOX", request.url.parameters["collections"])
                        putJsonArray("items") {
                            if (ownCompany && archived)
                                addJsonObject {
                                    put("collection", "INBOX")
                                    put("id", firstId)
                                    put("version", 2)
                                    put("operation", "DELETE")
                                }
                        }
                        put("cursor", if (archived) "fixture-archived" else "fixture-current")
                        put("hasMore", false)
                        put("pendingPublication", false)
                        put("pollAfterSeconds", 5)
                        put("serverTime", "2026-10-11T00:00:00Z")
                    }
                else -> {
                    val id = path.substringAfterLast('/')
                    val index = ids.indexOf(id)
                    if (!ownCompany || index < 0 || (archived && id == firstId))
                        return """{"code":"inbox_item_not_found"}""" to HttpStatusCode.NotFound
                    buildJsonObject {
                        put("id", id)
                        put("announcementId", "20000000-0000-4000-8000-000000000001")
                        put("publicationId", "30000000-0000-4000-8000-000000000001")
                        put("version", 1)
                        put(
                            "title",
                            if (index == 0) "Office closure"
                            else "Team update " + (index + 1).toString().padStart(2, '0'),
                        )
                        put(
                            "body",
                            if (index == 0) firstBody else "Please review the latest team schedule.",
                        )
                        put("acknowledgementRequired", index == 0)
                        put(
                            "deliveredAt",
                            (Instant.parse("2026-10-10T09:00:00Z") - index.minutes).toString(),
                        )
                        put("readAt", JsonNull)
                        put("acknowledgedAt", JsonNull)
                    }
                }
            }
        return body.toString() to HttpStatusCode.OK
    }
}
