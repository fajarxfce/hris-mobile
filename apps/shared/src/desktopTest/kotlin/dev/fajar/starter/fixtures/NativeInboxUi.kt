package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Independent wire fixture for the native inbox, with no runtime mock registration. */
class NativeInboxUi {
    @Volatile var offline = false
    @Volatile var archived = false
    @Volatile var loseNextActionResponse = false
    @Volatile var rejectNextAction = false
    @Volatile var holdNextAction = false
    val actionStarted = CompletableDeferred<Unit>()
    val actionReleased = CompletableDeferred<Unit>()
    val actions = CopyOnWriteArrayList<InboxActionAttempt>()
    private val receipts = mutableMapOf<String, Pair<InboxActionAttempt, String>>()
    private var version = 1L
    private var read = false
    private var acknowledged = false
    val firstId = "10000000-0000-4000-8000-000000000001"
    val firstBody =
        "The office will be closed on Friday. Please check with your manager for coverage."
    private val ids = (1..36).map { "10000000-0000-4000-8000-" + it.toString().padStart(12, '0') }

    suspend fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val path = request.url.encodedPath
        if (!path.contains("/sync/") && !path.contains("/inbox/")) return null
        if (offline) throw IOException("Simulated offline inbox")
        val company = path.split('/').getOrNull(4)
        check(company in setOf(NativeUiServer.COMPANY_ONE, NativeUiServer.COMPANY_TWO))
        val ownCompany = company == NativeUiServer.COMPANY_ONE
        if (request.method == HttpMethod.Post) return act(request, ownCompany)
        check(request.method == HttpMethod.Get)
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
                                            put("version", if (id == firstId) version else 1)
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
                            if (ownCompany && (archived || version > 1))
                                addJsonObject {
                                    put("collection", "INBOX")
                                    put("id", firstId)
                                    put("version", if (archived) version + 1 else version)
                                    put("operation", if (archived) "DELETE" else "UPSERT")
                                }
                        }
                        put(
                            "cursor",
                            if (archived) "fixture-archived" else "fixture-current-$version",
                        )
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
                        put("version", if (id == firstId) version else 1)
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
                        put(
                            "readAt",
                            if (index == 0 && read) JsonPrimitive("2026-10-11T00:00:00Z")
                            else JsonNull,
                        )
                        put(
                            "acknowledgedAt",
                            if (index == 0 && acknowledged) JsonPrimitive("2026-10-11T00:00:00Z")
                            else JsonNull,
                        )
                    }
                }
            }
        return body.toString() to HttpStatusCode.OK
    }

    private suspend fun act(
        request: HttpRequestData,
        ownCompany: Boolean,
    ): Pair<String, HttpStatusCode> {
        val segments = request.url.encodedPath.split('/')
        val resource = segments[segments.lastIndex - 1]
        val action = segments.last()
        check(action in setOf("read", "acknowledge"))
        val operation = requireNotNull(request.headers["Idempotency-Key"])
        java.util.UUID.fromString(operation)
        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        assertEquals(setOf("expectedVersion"), body.keys)
        val expected = body.getValue("expectedVersion").jsonPrimitive.long
        val attempt = InboxActionAttempt(operation, expected, action)
        actions += attempt
        if (!ownCompany || archived || resource != firstId)
            return """{"code":"inbox_item_not_found"}""" to HttpStatusCode.NotFound
        receipts[operation]?.let { (original, receipt) ->
            assertEquals(original, attempt)
            return receipt to HttpStatusCode.OK
        }
        if (rejectNextAction) {
            rejectNextAction = false
            read = true
            version++
        }
        if (expected != version) return """{"code":"stale_version"}""" to HttpStatusCode.Conflict
        if ((action == "read" && !read) || (action == "acknowledge" && !acknowledged)) {
            read = true
            if (action == "acknowledge") acknowledged = true
            version++
        }
        val receipt = """{"id":"$firstId","version":$version}"""
        receipts[operation] = attempt to receipt
        if (holdNextAction) {
            holdNextAction = false
            actionStarted.complete(Unit)
            withContext(NonCancellable) { actionReleased.await() }
        }
        if (loseNextActionResponse) {
            loseNextActionResponse = false
            throw IOException("Fixture response loss after commit")
        }
        return receipt to HttpStatusCode.OK
    }
}

data class InboxActionAttempt(
    val operationId: String,
    val expectedVersion: Long,
    val action: String,
)
