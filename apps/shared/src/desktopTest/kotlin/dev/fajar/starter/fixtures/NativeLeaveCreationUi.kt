package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*

/** Submission commits and receipts survive the transport's simulated response loss. */
class NativeLeaveCreationUi {
    @Volatile var loseNextResponse = false
    @Volatile var commits = 0
    @Volatile var attachmentRequired = false
    val previews = CopyOnWriteArrayList<JsonObject>()
    val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val receipts = mutableMapOf<String, Pair<String, String>>()
    private val created = mutableMapOf<String, JsonObject>()
    private val policy: JsonObject
        get() =
            JsonObject(
                nativeLeaveRequestFixture(null).getValue("policy").jsonObject +
                    ("attachmentRequired" to JsonPrimitive(attachmentRequired))
            )

    @Synchronized
    fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val path = request.url.encodedPath
        if (!path.contains("/companies/${NativeUiServer.COMPANY_ONE}/")) return null
        if (path.endsWith("/leave/types"))
            return effectiveTypes(request).toString() to HttpStatusCode.OK
        if (path.endsWith("/leave/me/requests/preview")) {
            val input = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            previews += input
            return preview(input).toString() to HttpStatusCode.OK
        }
        if (request.method == HttpMethod.Post && path.endsWith("/leave/requests")) {
            val body = (request.body as TextContent).text
            val operation = checkNotNull(request.headers["Idempotency-Key"])
            requests += operation to body
            receipts[operation]?.let {
                check(it.first == body)
                return it.second to HttpStatusCode.OK
            }
            val input = Json.parseToJsonElement(body).jsonObject
            check(input.getValue("previewRevision").jsonPrimitive.content == "a".repeat(64))
            val attachments =
                input.getValue("attachmentRevisionIds").jsonArray.map { it.jsonPrimitive.content }
            check(
                attachments ==
                    if (attachmentRequired) listOf(NativeDocumentsUi.REVISION) else emptyList()
            )
            val id = input.getValue("id").jsonPrimitive.content
            check(id !in created)
            val calculated = preview(input)
            val submittedAt = Instant.now().toString()
            created[id] = buildJsonObject {
                nativeLeaveRequestFixture(null).forEach { (key, value) -> put(key, value) }
                put("id", id)
                put("version", 0)
                put("status", "PENDING")
                put("submittedAt", submittedAt)
                put("reason", input.getValue("reason"))
                put("policy", calculated.getValue("policy"))
                putJsonArray("attachments") {
                    if (attachmentRequired) add(NativeDocumentsUi.attachment())
                }
                put("days", calculated.getValue("days"))
                put("chargedDays", calculated.getValue("chargedDays"))
                putJsonObject("approval") {
                    put("id", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
                    put("status", "PENDING")
                    put("currentStep", 0)
                    put("version", 0)
                }
                putJsonArray("availableActions") { add("WITHDRAW") }
                putJsonObject("history") {
                    putJsonArray("items") {
                        addJsonObject {
                            put("version", 0)
                            put("kind", "SUBMITTED")
                            put("status", "PENDING")
                            put("recordedAt", submittedAt)
                            put("reason", input.getValue("reason"))
                        }
                    }
                    put("nextCursor", JsonNull)
                }
            }
            val receipt =
                buildJsonObject {
                        put("id", id)
                        put("version", 0)
                    }
                    .toString()
            receipts[operation] = body to receipt
            commits++
            if (loseNextResponse) {
                loseNextResponse = false
                throw IOException("Fixture response lost after commit")
            }
            return receipt to HttpStatusCode.OK
        }
        if (path.contains("/leave/me/requests/"))
            created[path.substringAfterLast('/')]?.let {
                return it.toString() to HttpStatusCode.OK
            }
        return null
    }

    private fun effectiveTypes(request: HttpRequestData): JsonObject {
        check(request.url.parameters["limit"] == "20")
        LocalDate.parse(checkNotNull(request.url.parameters["asOf"]))
        return buildJsonObject {
            putJsonArray("items") {
                addJsonObject {
                    put("id", policy.getValue("typeId"))
                    put("code", "ANNUAL")
                    put("name", "Annual leave")
                    put("effectiveFrom", "2026-01-01")
                    put("paid", true)
                    put("allowPartialDays", true)
                    put("attachmentRequired", attachmentRequired)
                    put("active", true)
                    put("version", 0)
                    put("appliedRevision", 0)
                }
            }
            put("nextCursor", JsonNull)
        }
    }

    private fun preview(input: JsonObject): JsonObject {
        check(input.getValue("employeeId").jsonPrimitive.content == NativePeopleUi.EMPLOYEE)
        check(input.getValue("typeId") == policy.getValue("typeId"))
        val (working, excluded) =
            input.getValue("days").jsonArray.partition {
                LocalDate.parse(it.jsonObject.getValue("workDate").jsonPrimitive.content)
                    .dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            }
        val amount =
            working.sumOf {
                if (it.jsonObject.getValue("portion").jsonPrimitive.content == "FULL") 2 else 1
            }
        check(amount in 1..25)
        return buildJsonObject {
            put("employeeId", NativePeopleUi.EMPLOYEE)
            put("evaluatedAt", Instant.now().toString())
            put("timezone", "Asia/Jakarta")
            put("policy", policy)
            put("revision", "a".repeat(64))
            put("chargedDays", daysText(amount))
            putJsonArray("days") {
                working.forEach { item ->
                    addJsonObject {
                        val fields = item.jsonObject
                        val date = fields.getValue("workDate").jsonPrimitive.content
                        val full = fields.getValue("portion").jsonPrimitive.content == "FULL"
                        put("workDate", date)
                        put("portion", fields.getValue("portion"))
                        put("chargedDays", if (full) "1" else "0.5")
                        put("startsAt", "${date}T02:00:00Z")
                        put("endsAt", "${date}T10:00:00Z")
                        put("plannedMinutes", 480)
                        put("chargedMinutes", if (full) 480 else 240)
                    }
                }
            }
            putJsonArray("excludedDates") {
                excluded.forEach { add(it.jsonObject.getValue("workDate")) }
            }
            putJsonArray("balances") {
                addJsonObject {
                    put("year", 2026)
                    put("availableDays", "12.5")
                    put("reservedDays", "2")
                    put("consumedDays", "1.5")
                    put("requestedDays", daysText(amount))
                    put("remainingDays", daysText(25 - amount))
                    put("version", 3)
                }
            }
        }
    }

    private fun daysText(halfDays: Int) =
        (halfDays / 2).toString() + if (halfDays % 2 == 1) ".5" else ""
}
