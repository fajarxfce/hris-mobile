package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

/** Fictional wire responses exercise the real API adapter, cache, DI and navigation graph. */
class NativeOvertimeUi {
    @Volatile var enabled = false
    @Volatile var offline = false
    @Volatile var failureCode: String? = null
    val listReads = AtomicInteger()
    val detailReads = AtomicInteger()
    val planningReads = AtomicInteger()
    val planCommits = AtomicInteger()
    val planRequests = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile var loseNextPlanResponse = false
    private val receivedPlans = mutableMapOf<String, String>()
    private val plannedDetails = mutableMapOf<String, JsonObject>()

    @Synchronized
    fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val path = request.url.encodedPath
        if (!enabled || !path.contains("/workforce/overtime")) return null
        if (offline) throw IOException("Overtime transport unavailable")
        failureCode?.let {
            return """{"code":"$it","title":"Private diagnostic"}""" to HttpStatusCode.Forbidden
        }
        if (path.endsWith("/overtime") && request.method == HttpMethod.Post) {
            val operation = requireNotNull(request.headers["Idempotency-Key"])
            val body = (request.body as TextContent).text
            val plan = Json.parseToJsonElement(body).jsonObject
            assertEquals("a".repeat(64), plan["planningRevision"]?.jsonPrimitive?.content)
            assertEquals(NativePeopleUi.EMPLOYEE, plan["employeeId"]?.jsonPrimitive?.content)
            assertEquals(0, plan["expectedEmploymentVersion"]?.jsonPrimitive?.long)
            planRequests += operation to body
            val previous = receivedPlans[operation]
            if (previous == null) {
                receivedPlans[operation] = body
                val id = plan.getValue("id").jsonPrimitive.content
                plannedDetails[id] = nativeOvertimePlannedFixture(plan)
                planCommits.incrementAndGet()
            } else assertEquals(previous, body)
            if (loseNextPlanResponse) {
                loseNextPlanResponse = false
                throw IOException("Plan response lost")
            }
            return """{"id":${plan.getValue("id")},"version":0}""" to HttpStatusCode.OK
        }
        assertEquals(HttpMethod.Get, request.method)
        if (path.endsWith("/planning/employees/${NativePeopleUi.EMPLOYEE}")) {
            planningReads.incrementAndGet()
            val date = LocalDate.parse(request.url.parameters["workDate"]).toString()
            return buildJsonObject {
                    putJsonObject("employee") {
                        put("id", NativePeopleUi.EMPLOYEE)
                        put("employeeNumber", "NUSA-001")
                        put("legalName", "Alex Morgan")
                        put("status", "ACTIVE")
                        put("version", 0)
                    }
                    put("workDate", date)
                    put("companyDate", "2026-10-12")
                    put("timezone", "Asia/Jakarta")
                    putJsonObject("schedule") {
                        put("workDate", date)
                        put("kind", "OFF")
                        put("origin", "ROSTER")
                        put("originVersion", 0)
                    }
                    put("revision", "a".repeat(64))
                }
                .toString() to HttpStatusCode.OK
        }
        val details = nativeOvertimeDetailsFixture()
        if (path.endsWith("/overtime")) {
            listReads.incrementAndGet()
            assertEquals(NativePeopleUi.EMPLOYEE, request.url.parameters["employeeId"])
            assertEquals("20", request.url.parameters["limit"])
            val from = LocalDate.parse(request.url.parameters["from"])
            val until = LocalDate.parse(request.url.parameters["until"])
            val visible =
                (listOf(details) + plannedDetails.values)
                    .map { it.getValue("request").jsonObject }
                    .filter {
                        LocalDate.parse(it.getValue("workDate").jsonPrimitive.content) in
                            from..until &&
                            request.url.parameters["status"] in listOf(null, "PLANNED")
                    }
            return buildJsonObject {
                    putJsonArray("items") { visible.forEach(::add) }
                    put("nextCursor", JsonNull)
                }
                .toString() to HttpStatusCode.OK
        }
        if (path.endsWith("/$REQUEST_ID")) {
            detailReads.incrementAndGet()
            assertEquals("10", request.url.parameters["historyLimit"])
            return details.toString() to HttpStatusCode.OK
        }
        plannedDetails[path.substringAfterLast('/')]?.let {
            detailReads.incrementAndGet()
            assertEquals("10", request.url.parameters["historyLimit"])
            return it.toString() to HttpStatusCode.OK
        }
        return """{"code":"overtime_request_not_found"}""" to HttpStatusCode.NotFound
    }

    companion object {
        const val REQUEST_ID = "66666666-6666-4666-8666-666666666666"
    }
}

private fun nativeOvertimePlannedFixture(plan: JsonObject): JsonObject {
    val base = nativeOvertimeDetailsFixture()
    val interval = plan.getValue("requested").jsonObject
    val start = Instant.parse(interval.getValue("startsAt").jsonPrimitive.content)
    val end = Instant.parse(interval.getValue("endsAt").jsonPrimitive.content)
    val now = Instant.now().toString()
    val request =
        JsonObject(
            base.getValue("request").jsonObject +
                mapOf(
                    "id" to plan.getValue("id"),
                    "workDate" to plan.getValue("workDate"),
                    "reason" to plan.getValue("reason"),
                    "createdAt" to JsonPrimitive(now),
                    "requested" to
                        JsonObject(
                            interval +
                                ("workedMinutes" to
                                    JsonPrimitive(
                                        Duration.between(start, end).toMinutes() -
                                            interval.getValue("breakMinutes").jsonPrimitive.int
                                    ))
                        ),
                    "schedule" to
                        JsonObject(
                            base.getValue("request").jsonObject.getValue("schedule").jsonObject +
                                ("workDate" to plan.getValue("workDate"))
                        ),
                )
        )
    val initial =
        base.getValue("history").jsonObject.getValue("items").jsonArray.single().jsonObject
    return JsonObject(
        base +
            mapOf(
                "request" to request,
                "history" to
                    buildJsonObject {
                        putJsonArray("items") {
                            add(
                                JsonObject(
                                    initial +
                                        mapOf(
                                            "recordedAt" to JsonPrimitive(now),
                                            "reason" to plan.getValue("reason"),
                                        )
                                )
                            )
                        }
                        put("nextCursor", JsonNull)
                    },
            )
    )
}

fun nativeOvertimeDetailsFixture(approved: Boolean = false): JsonObject = buildJsonObject {
    val approvalId = "88888888-8888-4888-8888-888888888888"
    val requested = buildJsonObject {
        put("startsAt", "2026-10-12T10:00:00Z")
        put("endsAt", "2026-10-12T12:00:00Z")
        put("breakMinutes", 0)
        put("workedMinutes", 120)
    }
    val actual = buildJsonObject {
        put("startsAt", "2026-10-12T10:05:00Z")
        put("endsAt", "2026-10-12T11:55:00Z")
        put("breakMinutes", 0)
        put("workedMinutes", 110)
    }
    putJsonObject("request") {
        put("id", NativeOvertimeUi.REQUEST_ID)
        put("employeeId", NativePeopleUi.EMPLOYEE)
        put("employeeNumber", "NUSA-001")
        put("employeeName", "Alex Morgan")
        put("requesterAccountId", NativePeopleUi.ACCOUNT)
        put("authorId", NativePeopleUi.ACCOUNT)
        put("createdAt", "2026-10-12T01:00:00Z")
        put("workDate", "2026-10-12")
        put("timezone", "Asia/Jakarta")
        putJsonObject("schedule") {
            put("workDate", "2026-10-12")
            put("kind", "OFF")
            put("origin", "ROSTER")
            put("originVersion", 0)
        }
        put("requested", requested)
        put("reason", "Inventory count")
        put("status", if (approved) "APPROVED" else "PLANNED")
        put("actual", if (approved) actual else JsonNull)
        put("submittedBy", if (approved) JsonPrimitive(NativePeopleUi.ACCOUNT) else JsonNull)
        put("submittedAt", if (approved) JsonPrimitive("2026-10-12T13:00:00Z") else JsonNull)
        put("approvalId", if (approved) JsonPrimitive(approvalId) else JsonNull)
        put("approvedMinutes", if (approved) 110 else 0)
        put("decidedAt", if (approved) JsonPrimitive("2026-10-12T14:00:00Z") else JsonNull)
        put("version", if (approved) 2 else 0)
    }
    if (approved)
        putJsonObject("approval") {
            put("id", approvalId)
            put("status", "APPROVED")
            put("currentStep", 0)
            put("version", 1)
            putJsonArray("stages") { addJsonArray { add("99999999-9999-4999-8999-999999999999") } }
        }
    else put("approval", JsonNull)
    putJsonObject("history") {
        putJsonArray("items") {
            for (revision in (if (approved) 2 else 0) downTo 0) addJsonObject {
                put("revision", revision)
                put("kind", listOf("PLANNED", "ACTUAL_SUBMITTED", "DECIDED")[revision])
                put("status", listOf("PLANNED", "PENDING", "APPROVED")[revision])
                put("actual", if (revision > 0) actual else JsonNull)
                put("approvedMinutes", if (revision == 2) 110 else 0)
                put("approvalId", if (revision > 0) JsonPrimitive(approvalId) else JsonNull)
                put(
                    "actorId",
                    if (revision == 2) "99999999-9999-4999-8999-999999999999"
                    else NativePeopleUi.ACCOUNT,
                )
                put(
                    "recordedAt",
                    listOf("2026-10-12T01:00:00Z", "2026-10-12T13:00:00Z", "2026-10-12T14:00:00Z")[
                        revision],
                )
                put(
                    "reason",
                    listOf("Inventory count", "Count completed", "Approved hours")[revision],
                )
            }
        }
        put("nextCursor", JsonNull)
    }
    putJsonArray("actions") {
        if (!approved) {
            add("SUBMIT_ACTUAL")
            add("WITHDRAW")
        }
    }
}
