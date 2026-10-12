package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*

/** Fictional transport used only by assembled application tests. */
class NativeLeaveUi {
    @Volatile var enabled = false
    @Volatile var offline = false
    @Volatile var rejectDetails = false
    val years = CopyOnWriteArrayList<Int>()
    val statuses = CopyOnWriteArrayList<String>()
    val historyPages = CopyOnWriteArrayList<String>()
    val actions = NativeLeaveActionsUi()
    val creation = NativeLeaveCreationUi()

    fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val path = request.url.encodedPath
        if (!path.contains("/leave/")) return null
        check(enabled)
        if (offline) throw IOException("Leave test connection unavailable")
        creation.response(request)?.let {
            return it
        }
        if (request.method == HttpMethod.Post) return actions.response(request)
        val company = path.split('/')[4]
        if (
            path.contains("/me/requests/") &&
                (rejectDetails || company != NativeUiServer.COMPANY_ONE)
        )
            return """{"code":"leave_request_not_found"}""" to HttpStatusCode.NotFound
        val body =
            when {
                path.contains("/me/requests/") -> {
                    check(path.endsWith("/dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
                    check(request.url.parameters["historyLimit"] == "10")
                    val after = request.url.parameters["historyAfter"]
                    historyPages += after ?: "LATEST"
                    actions.details(after?.toLong())
                }
                path.endsWith("/me/employments") ->
                    buildJsonObject {
                        put("accountId", NativePeopleUi.ACCOUNT)
                        put("companyId", company)
                        put("asOf", "2026-10-12")
                        put("timezone", "Asia/Jakarta")
                        putJsonObject("employments") {
                            putJsonArray("items") {
                                if (company == NativeUiServer.COMPANY_ONE)
                                    add(
                                        buildJsonObject {
                                            put("id", NativePeopleUi.EMPLOYEE)
                                            put("employeeNumber", "NUSA-001")
                                            put("status", "ACTIVE")
                                            put("version", 0)
                                        }
                                    )
                            }
                            put("nextCursor", JsonNull)
                        }
                    }
                path.endsWith("/balances") -> {
                    val year = checkNotNull(request.url.parameters["year"]).toInt()
                    years += year
                    balancePage(year)
                }
                path.endsWith("/requests") -> {
                    val status = request.url.parameters["status"]
                    statuses += status ?: "ALL"
                    buildJsonObject {
                        putJsonArray("items") {
                            if (status == null || status == "APPROVED")
                                add(
                                    buildJsonObject {
                                        put("id", "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
                                        put("employeeId", NativePeopleUi.EMPLOYEE)
                                        put("typeCode", "ANNUAL")
                                        put("typeName", "Annual leave")
                                        put("from", "2026-10-15")
                                        put("until", "2026-10-16")
                                        put("chargedDays", "1.5")
                                        put("status", "APPROVED")
                                        put("submittedAt", "2026-10-10T02:00:00Z")
                                        put("version", 1)
                                    }
                                )
                        }
                        put("nextCursor", JsonNull)
                    }
                }
                else -> error("Unexpected leave test request")
            }
        return body.toString() to HttpStatusCode.OK
    }

    private fun balancePage(year: Int): JsonObject = buildJsonObject {
        putJsonObject("employee") { put("id", NativePeopleUi.EMPLOYEE) }
        put("year", year)
        putJsonObject("balances") {
            putJsonArray("items") {
                if (year == 2026)
                    add(
                        buildJsonObject {
                            put("typeId", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
                            put("typeCode", "ANNUAL")
                            put("typeName", "Annual leave")
                            putJsonObject("balance") {
                                put("accountId", "cccccccc-cccc-4ccc-8ccc-cccccccccccc")
                                put("year", year)
                                put("availableDays", "12.5")
                                put("reservedDays", "2")
                                put("consumedDays", "1.5")
                                put("version", 3)
                                put("closed", false)
                            }
                        }
                    )
            }
            put("nextCursor", JsonNull)
        }
    }
}
