package dev.fajar.starter.fixtures

import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import java.io.IOException
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Test-only wire contract. Runtime composition has no mock authentication transport. */
class NativeUiServer(
    private val requireMfa: Boolean = false,
    private val existingAuthenticator: Boolean = false,
    private val loseConfirmation: Boolean = false,
    private val beforeRequest: suspend (HttpRequestData) -> Unit = {},
) {
    private val requests = Mutex()
    private var verified = false
    var refreshes = 0
        private set

    var confirmations = 0
        private set

    val unexpectedPaths = mutableListOf<String>()
    val people = NativePeopleUi()
    val directory = NativeDirectoryUi()
    val workforce = NativeWorkforceUi()
    val overtime = NativeOvertimeUi()
    val leave = NativeLeaveUi()
    val documents = NativeDocumentsUi()
    val payroll = NativePayslipUi()
    val expenses = NativeExpenseUi()
    val inbox = NativeInboxUi()
    val deviceSessions = NativeSessionsUi()
    val companyPolicy = NativeCompanyPolicyUi()
    var rejectSessionAccessOnce = false

    suspend fun expireVerification() = requests.withLock { verified = false }

    fun engine() = MockEngine { request ->
        beforeRequest(request)
        requests.withLock {
            val path = request.url.encodedPath
            assertNativeHeaders(request)
            val headers = headersOf(HttpHeaders.ContentType, "application/json")
            companyPolicy.response(path)?.let { (body, status) ->
                return@withLock respond(body, status, headers)
            }
            if ((path.contains("/sync/") || path.contains("/inbox/")) && requireMfa && !verified)
                return@withLock respond(
                    """{"code":"mfa_required"}""",
                    HttpStatusCode.Forbidden,
                    headers,
                )
            inbox.response(request)?.let { (body, status) ->
                return@withLock respond(body, status, headers)
            }
            (workforce.response(request) ?: overtime.response(request))?.let { (body, status) ->
                return@withLock respond(body, status, headers)
            }
            (leave.response(request) ?: expenses.response(request))?.let { (body, status) ->
                return@withLock respond(body, status, headers)
            }
            (documents.downloads.response(request, this) ?: payroll.response(request, this))?.let {
                return@withLock it
            }
            documents.response(request)?.let { (body, status) ->
                return@withLock respond(body, status, headers)
            }
            if (path.startsWith("/api/v1/auth/native/sessions") && rejectSessionAccessOnce) {
                rejectSessionAccessOnce = false
                return@withLock respond(
                    """{"code":"session_expired"}""",
                    HttpStatusCode.Unauthorized,
                    headers,
                )
            }
            deviceSessions.response(path, request.method)?.let { (body, status) ->
                return@withLock respond(body, status, headers)
            }
            (directory.response(request) ?: people.response(path))?.let { (body, status) ->
                return@withLock if (requireMfa && !verified)
                    respond("""{"code":"mfa_required"}""", HttpStatusCode.Forbidden, headers)
                else respond(body, status, headers)
            }
            val body =
                request.body
                    .toByteArray()
                    .decodeToString()
                    .takeIf { it.isNotEmpty() }
                    ?.let { Json.parseToJsonElement(it).jsonObject }
            if (request.method == HttpMethod.Post)
                UUID.fromString(request.headers["Idempotency-Key"])
            when (path) {
                "/api/v1/auth/native/login" -> respond(signIn(body).toString(), headers = headers)
                "/api/v1/auth/native/refresh" -> {
                    refreshes++
                    respond(credentials().toString(), headers = headers)
                }
                "/api/v1/me" -> {
                    respond(snapshot().toString(), headers = headers)
                }
                "/api/v1/auth/native/mfa/enrollment" ->
                    respond(
                        enrollment(request.headers["Idempotency-Key"]).toString(),
                        headers = headers,
                    )
                "/api/v1/auth/native/mfa/enrollment/confirm" ->
                    respond(confirmedEnrollment().toString(), headers = headers)
                "/api/v1/auth/native/mfa/verify" -> {
                    if (body?.get("code")?.jsonPrimitive?.content != "123456")
                        respond(
                            """{"code":"invalid_mfa_code","title":"Private diagnostic must not be displayed"}""",
                            HttpStatusCode.Unauthorized,
                            headers,
                        )
                    else {
                        verified = true
                        respond(
                            buildJsonObject { put("credentials", credentials()) }.toString(),
                            headers = headers,
                        )
                    }
                }
                "/api/v1/companies/$COMPANY_ONE/me/access",
                "/api/v1/companies/$COMPANY_TWO/me/access" -> {
                    respond(companyAccess(path).toString(), headers = headers)
                }
                else -> {
                    unexpectedPaths += path
                    respond(
                        """{"code":"unexpected_test_request"}""",
                        HttpStatusCode.NotFound,
                        headers,
                    )
                }
            }
        }
    }

    private fun assertNativeHeaders(request: HttpRequestData) {
        assertEquals("ANDROID", request.headers["X-HRIS-Client-Platform"])
        assertEquals("1", request.headers["X-HRIS-Client-Build"])
        if (
            request.url.encodedPath !in
                setOf("/api/v1/auth/native/login", "/api/v1/auth/native/refresh")
        )
            assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("Bearer ") == true)
    }

    private fun signIn(body: JsonObject?): JsonObject {
        assertEquals("alex@example.com", body?.get("email")?.jsonPrimitive?.content)
        assertEquals("Fictional-password-123!", body?.get("password")?.jsonPrimitive?.content)
        assertEquals("HRIS Mobile (android)", body?.get("deviceName")?.jsonPrimitive?.content)
        verified = false
        return buildJsonObject {
            put("account", account())
            put("credentials", credentials())
        }
    }

    private fun companyAccess(path: String): JsonObject {
        assertFalse(requireMfa && !verified)
        return buildJsonObject {
            put("companyId", path.split('/')[4])
            putJsonArray("permissions") {
                add("people.self.read")
                add("people.directory.read")
                add("announcements.read")
                add("attendance.self.record")
                if (overtime.enabled) add("overtime.self.manage")
                if (leave.enabled) add("leave.self.manage")
                if (payroll.enabled) add("payroll.self.read")
                if (expenses.enabled) add("expenses.self.manage")
                if (documents.enabled) {
                    add("documents.self.read")
                    if (documents.uploadEnabled) add("documents.self.upload")
                }
            }
        }
    }

    private fun enrollment(operationId: String?) = buildJsonObject {
        put("operationId", operationId)
        put("secret", "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        put(
            "otpauthUri",
            "otpauth://totp/HRIS:alex?secret=ABCDEFGHIJKLMNOPQRSTUVWXYZ234567&issuer=HRIS",
        )
        put("expiresAt", (Clock.System.now() + 5.minutes).toString())
    }

    private fun confirmedEnrollment(): JsonObject {
        confirmations++
        verified = true
        if (loseConfirmation) throw IOException("Simulated lost response")
        return buildJsonObject {
            put("credentials", credentials())
            putJsonArray("recoveryCodes") {
                repeat(10) { add("RECOVERY-TEST-${it.toString().padStart(2, '0')}") }
            }
        }
    }

    private fun credentials() = buildJsonObject {
        put("sessionId", "44444444-4444-4444-8444-444444444444")
        put("accessToken", if (verified) "verified-access" else "pending-access")
        put("refreshToken", if (verified) "verified-refresh" else "pending-refresh")
        put("accessExpiresAt", (Clock.System.now() + 10.minutes).toString())
        put("sessionExpiresAt", "2099-01-01T00:00:00Z")
        put("tokenType", "Bearer")
    }

    private fun account() = buildJsonObject {
        put("id", "11111111-1111-4111-8111-111111111111")
        put("email", "alex@example.com")
        put("displayName", "Alex Morgan")
        put("mfaConfigured", existingAuthenticator || verified)
    }

    private fun snapshot() = buildJsonObject {
        put("account", account())
        putJsonArray("permissions") {}
        putJsonArray("companies") {
            if (!requireMfa || verified) {
                addJsonObject {
                    put("id", COMPANY_ONE)
                    put("name", "PT Nusa Digital")
                    put("code", "NUSA")
                    put("timezone", "Asia/Jakarta")
                }
                addJsonObject {
                    put("id", COMPANY_TWO)
                    put("name", "PT Lintas Karya")
                    put("code", "LINTAS")
                    put("timezone", "Asia/Jakarta")
                }
            }
        }
        putJsonObject("assurance") {
            put("required", requireMfa)
            put("verified", verified)
            put("setupAvailable", true)
            put("validUntil", if (verified) "2099-01-01T00:00:00Z" else null)
            put("recentUntil", if (verified) "2099-01-01T00:00:00Z" else null)
        }
    }

    companion object {
        const val COMPANY_ONE = "22222222-2222-4222-8222-222222222222"
        const val COMPANY_TWO = "33333333-3333-4333-8333-333333333333"
    }
}
