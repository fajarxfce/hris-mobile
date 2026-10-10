package dev.fajar.starter.fixtures

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.*

/** Fictional wire responses for profile integration tests only. */
class NativePeopleUi {
    @Volatile var denied = false

    fun response(path: String): Pair<String, HttpStatusCode>? {
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

    private fun employee() = buildJsonObject {
        put("id", EMPLOYEE)
        put("companyId", NativeUiServer.COMPANY_ONE)
        put("employeeNumber", "NUSA-001")
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

    private fun employmentDetails() = buildJsonObject {
        put("asOf", "2026-10-01")
        put("employee", employee())
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
        const val EMPLOYEE = "55555555-5555-4555-8555-555555555555"
        const val PERSON = "66666666-6666-4666-8666-666666666666"
        const val ACCOUNT = "11111111-1111-4111-8111-111111111111"
        const val DEPARTMENT = "77777777-7777-4777-8777-777777777777"
        const val POSITION = "88888888-8888-4888-8888-888888888888"
    }
}
