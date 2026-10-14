package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Fictional wire responses for profile integration tests only. */
class NativePeopleUi {
    @Volatile var denied = false

    @Volatile var offline = false
    @Volatile var employmentCount = 1
    @Volatile var holdNext = false
    val started = CompletableDeferred<Unit>()
    val released = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val queries = CopyOnWriteArrayList<String>()
    val recordReads = CopyOnWriteArrayList<String>()

    suspend fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val path = request.url.encodedPath
        val sync =
            path.contains("/sync/") && request.url.parameters["collections"] == "SELF_PROFILES"
        val canonical = path.contains("/employees/me/profiles/")
        if (sync || canonical) {
            if (sync) {
                assertEquals("50", request.url.parameters["limit"])
                queries.add(path.substringAfterLast('/'))
                if (holdNext) {
                    holdNext = false
                    started.complete(Unit)
                    try {
                        released.await()
                    } catch (failure: CancellationException) {
                        cancelled.complete(Unit)
                        throw failure
                    }
                }
            }
            if (offline) throw IOException("Simulated offline profile")
            if (denied) return """{"code":"access_denied"}""" to HttpStatusCode.Forbidden
            val rows =
                if (path.split('/')[4] == NativeUiServer.COMPANY_ONE)
                    (1..employmentCount).map { index ->
                        buildJsonObject {
                            put("id", if (index == 1) EMPLOYEE else SECOND_EMPLOYEE)
                            put("version", 17)
                            put("timezone", "Asia/Jakarta")
                            put("employment", employmentDetails(index))
                            put("profile", personalDetails())
                        }
                    }
                else emptyList()
            if (canonical) {
                val id = path.substringAfterLast('/')
                recordReads.add(id)
                val row =
                    rows.singleOrNull { it.getValue("id").jsonPrimitive.content == id }
                        ?: return """{"code":"self_profile_not_found"}""" to HttpStatusCode.NotFound
                return row.toString() to HttpStatusCode.OK
            }
            val bootstrap = path.endsWith("/bootstrap")
            return buildJsonObject {
                    if (bootstrap) putJsonArray("collections") { add("SELF_PROFILES") }
                    putJsonArray("items") {
                        if (bootstrap)
                            rows.forEach { row ->
                                addJsonObject {
                                    put("collection", "SELF_PROFILES")
                                    put("id", row.getValue("id"))
                                    put("version", row.getValue("version"))
                                }
                            }
                    }
                    if (bootstrap) {
                        put("nextCursor", JsonNull)
                        put("changesCursor", "profile-head")
                    } else {
                        put("cursor", "profile-head")
                        put("hasMore", false)
                        put("pendingPublication", false)
                        put("pollAfterSeconds", 5)
                    }
                    put("serverTime", "2026-10-14T00:00:00Z")
                }
                .toString() to HttpStatusCode.OK
        }
        val base = "/api/v1/companies/${NativeUiServer.COMPANY_ONE}"
        val body =
            when (path) {
                "$base/me/employments" -> directory(linked = true)
                "/api/v1/companies/${NativeUiServer.COMPANY_TWO}/me/employments" ->
                    directory(linked = false)
                "$base/employees/$EMPLOYEE/employment" -> employmentDetails()
                "$base/employees/$EMPLOYEE/profile" -> personalDetails()
                else -> return null
            }
        return if (denied)
            """{"code":"access_denied","title":"Private server detail"}""" to
                HttpStatusCode.Forbidden
        else body.toString() to HttpStatusCode.OK
    }

    private fun directory(linked: Boolean) = buildJsonObject {
        put("asOf", "2026-10-01")
        put("timezone", "Asia/Jakarta")
        putJsonObject("employments") {
            putJsonArray("items") { if (linked) add(employee()) }
            put("nextCursor", JsonNull)
        }
    }

    private fun employee(index: Int = 1) = buildJsonObject {
        put("id", if (index == 1) EMPLOYEE else SECOND_EMPLOYEE)
        put("companyId", NativeUiServer.COMPANY_ONE)
        put("employeeNumber", if (index == 1) "NUSA-001" else "NUSA-002")
        put("version", 0)
        put("appliedRevision", 0)
        putJsonObject("person") {
            put("id", PERSON)
            put("accountId", ACCOUNT)
            put("legalName", "Alex Morgan")
            put("email", "alex@example.com")
        }
        putJsonObject("terms") {
            put("effectiveFrom", "2025-01-01")
            put("startDate", "2025-01-01")
            put("status", "ACTIVE")
            put("contract", "PERMANENT")
            put("departmentId", DEPARTMENT)
            put("positionId", POSITION)
        }
    }

    private fun employmentDetails(index: Int = 1) = buildJsonObject {
        put("asOf", "2026-10-01")
        put("employee", employee(index))
        putJsonObject("department") {
            put("id", DEPARTMENT)
            put("code", "ENG")
            put("name", "Engineering")
            put("active", true)
        }
        putJsonObject("position") {
            put("id", POSITION)
            put("code", "MOBILE")
            put("name", "Mobile Engineer")
            put("active", true)
        }
    }

    private fun personalDetails() = buildJsonObject {
        put("personId", PERSON)
        put("ownerCompanyId", NativeUiServer.COMPANY_ONE)
        put("accountId", ACCOUNT)
        put("legalName", "Alex Morgan")
        put("birthDate", "1996-02-29")
        put("nationality", "ID")
        put("email", "alex@example.com")
        put("version", 0)
    }

    companion object {
        const val SECOND_EMPLOYEE = "55555555-5555-4555-8555-555555555556"
        const val EMPLOYEE = "55555555-5555-4555-8555-555555555555"
        const val PERSON = "66666666-6666-4666-8666-666666666666"
        const val ACCOUNT = "11111111-1111-4111-8111-111111111111"
        const val DEPARTMENT = "77777777-7777-4777-8777-777777777777"
        const val POSITION = "88888888-8888-4888-8888-888888888888"
    }
}
