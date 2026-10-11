package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*

/** Fictional transport used only by assembled application tests. */
class NativeLeaveUi {
    @Volatile var enabled = false
    @Volatile var offline = false
    val years = CopyOnWriteArrayList<Int>()
    val statuses = CopyOnWriteArrayList<String>()

    fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val path = request.url.encodedPath
        if (!path.contains("/leave/")) return null
        check(enabled)
        if (offline) throw IOException("Leave test connection unavailable")
        val company = path.split('/')[4]
        val body =
            when {
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
                    buildJsonObject {
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
                                                put(
                                                    "accountId",
                                                    "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                                                )
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
}
