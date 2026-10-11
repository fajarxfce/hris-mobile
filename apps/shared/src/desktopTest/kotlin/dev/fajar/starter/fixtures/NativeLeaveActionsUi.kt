package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*

/** A transactional fixture: a lost response does not remove its original operation receipt. */
class NativeLeaveActionsUi {
    @Volatile var loseNextResponse = false
    private var version = 12L
    private var status = "APPROVED"
    private val receipts = mutableMapOf<String, Pair<String, String>>()
    private val changes = mutableListOf<JsonObject>()
    val requests = CopyOnWriteArrayList<Pair<String, String>>()

    @Synchronized
    fun response(request: HttpRequestData): Pair<String, HttpStatusCode> {
        val path = request.url.encodedPath
        check(path.contains("/companies/${NativeUiServer.COMPANY_ONE}/leave/requests/"))
        val operation = checkNotNull(request.headers["Idempotency-Key"])
        val body = (request.body as TextContent).text
        val identity = path + "\n" + body
        requests += operation to identity
        receipts[operation]?.let {
            check(it.first == identity)
            return it.second to HttpStatusCode.OK
        }
        val fields = Json.parseToJsonElement(body).jsonObject
        if (fields.getValue("version").jsonPrimitive.long != version)
            return """{"code":"stale_version"}""" to HttpStatusCode.Conflict
        val reason = fields.getValue("reason").jsonPrimitive.content
        check(reason.isNotBlank())
        val kind =
            when {
                path.endsWith("/cancellation") && status == "APPROVED" -> {
                    status = "CANCELLATION_PENDING"
                    "CANCELLATION_REQUESTED"
                }
                path.endsWith("/withdraw") && status == "CANCELLATION_PENDING" -> {
                    status = "APPROVED"
                    "WITHDRAWN"
                }
                else -> error("Unexpected leave action in UI fixture")
            }
        version++
        changes += buildJsonObject {
            put("version", version)
            put("kind", kind)
            put("status", status)
            put("recordedAt", "2026-10-12T01:${(version - 12).toString().padStart(2, '0')}:00Z")
            put("reason", reason)
        }
        val receipt = """{"id":"dddddddd-dddd-4ddd-8ddd-dddddddddddd","version":$version}"""
        receipts[operation] = identity to receipt
        if (loseNextResponse) {
            loseNextResponse = false
            throw IOException("Fixture response lost after commit")
        }
        return receipt to HttpStatusCode.OK
    }

    @Synchronized
    fun details(after: Long?): JsonObject {
        if (version == 12L) return nativeLeaveRequestFixture(after)
        val original = nativeLeaveRequestFixture(null)
        val history =
            (changes.asReversed() +
                    original.getValue("history").jsonObject.getValue("items").jsonArray +
                    nativeLeaveRequestFixture(3)
                        .getValue("history")
                        .jsonObject
                        .getValue("items")
                        .jsonArray)
                .filter {
                    after == null || it.jsonObject.getValue("version").jsonPrimitive.long < after
                }
                .take(10)
        return buildJsonObject {
            original.forEach { (key, value) -> put(key, value) }
            put("version", version)
            put("status", status)
            putJsonArray("availableActions") {
                add(if (status == "APPROVED") "REQUEST_CANCELLATION" else "WITHDRAW")
            }
            putJsonObject("cancellation") {
                put("id", "ffffffff-ffff-4fff-8fff-ffffffffffff")
                put("status", if (status == "APPROVED") "CANCELLED" else "PENDING")
                put("currentStep", 0)
                put("version", version - 12)
            }
            putJsonObject("history") {
                put("items", JsonArray(history))
                put(
                    "nextCursor",
                    if (history.size == 10)
                        history.last().jsonObject.getValue("version").jsonPrimitive.content
                    else null,
                )
            }
        }
    }
}
