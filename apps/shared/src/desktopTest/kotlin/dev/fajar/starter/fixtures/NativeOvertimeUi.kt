package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.time.LocalDate
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

    fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val path = request.url.encodedPath
        if (!enabled || !path.contains("/workforce/overtime")) return null
        assertEquals(HttpMethod.Get, request.method)
        if (offline) throw IOException("Overtime transport unavailable")
        failureCode?.let {
            return """{"code":"$it","title":"Private diagnostic"}""" to HttpStatusCode.Forbidden
        }
        val details = nativeOvertimeDetailsFixture()
        if (path.endsWith("/overtime")) {
            listReads.incrementAndGet()
            assertEquals(NativePeopleUi.EMPLOYEE, request.url.parameters["employeeId"])
            assertEquals("20", request.url.parameters["limit"])
            val from = LocalDate.parse(request.url.parameters["from"])
            val until = LocalDate.parse(request.url.parameters["until"])
            val matches =
                LocalDate.parse("2026-10-12") in from..until &&
                    request.url.parameters["status"] in listOf(null, "PLANNED")
            return buildJsonObject {
                    putJsonArray("items") { if (matches) add(details.getValue("request")) }
                    put("nextCursor", JsonNull)
                }
                .toString() to HttpStatusCode.OK
        }
        if (path.endsWith("/$REQUEST_ID")) {
            detailReads.incrementAndGet()
            assertEquals("10", request.url.parameters["historyLimit"])
            return details.toString() to HttpStatusCode.OK
        }
        return """{"code":"overtime_request_not_found"}""" to HttpStatusCode.NotFound
    }

    companion object {
        const val REQUEST_ID = "66666666-6666-4666-8666-666666666666"
    }
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
