package dev.fajar.starter.expenses.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.data.api.ExpenseApi
import dev.fajar.starter.expenses.data.datasources.ApiExpenseDataSource
import dev.fajar.starter.expenses.data.repositories.RemoteExpenseRepository
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.network.RequestSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.YearMonth
import kotlinx.serialization.json.Json

class ExpenseTransportTest {
    @Test
    fun everyClaimReadPinsSelfScopeAndRequestsBoundedCompanyMonthPages() = runTest {
        val f = ExpenseDataFixture()
        val prefix = "/api/v1/companies/${f.access.companyId}/expenses"
        var calls = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    if (request.url.encodedPath != "$prefix/me/employments")
                        assertEquals("SELF", request.url.parameters["scope"])
                    val body =
                        when (request.url.encodedPath) {
                            "$prefix/me/employments" -> {
                                assertEquals("20", request.url.parameters["limit"])
                                Json.encodeToString(f.directory)
                            }
                            "$prefix/claims" -> {
                                assertEquals(f.employee.id, request.url.parameters["employmentId"])
                                assertEquals("2028-02-01", request.url.parameters["from"])
                                assertEquals("2028-02-29", request.url.parameters["until"])
                                assertEquals("DRAFT", request.url.parameters["status"])
                                assertEquals("20", request.url.parameters["limit"])
                                Json.encodeToString(f.page)
                            }
                            "$prefix/claims/${f.claim.id}" -> Json.encodeToString(f.claim)
                            "$prefix/claims/${f.claim.id}/history" -> {
                                assertEquals("20", request.url.parameters["limit"])
                                assertEquals("1", request.url.parameters["after"])
                                Json.encodeToString(f.history)
                            }
                            "$prefix/submissions/${f.submission.id}" ->
                                Json.encodeToString(f.submission)
                            "$prefix/claims/${f.claim.id}/payment-progress" ->
                                Json.encodeToString(f.payments)
                            else -> error("Unexpected expense route")
                        }
                    respond(
                        body,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repo = RemoteExpenseRepository(ApiExpenseDataSource(ExpenseApi(client)))
            val access = f.access.copy(permissions = setOf("expenses.manage"))
            assertIs<AppResult.Success<ExpenseDirectory>>(repo.directory(access, null))
            assertIs<AppResult.Success<ExpensePage<ExpenseSummary>>>(
                repo.list(
                    access,
                    ExpenseSelection(
                        f.employee.id,
                        YearMonth(2028, 2),
                        status = ExpenseStatus.DRAFT,
                    ),
                )
            )
            assertIs<AppResult.Success<ExpenseClaim>>(repo.claim(access, f.claim.id))
            assertIs<AppResult.Success<ExpensePage<ExpenseChange>>>(
                repo.history(access, f.claim.id, 1)
            )
            assertIs<AppResult.Success<ExpenseSubmission>>(repo.submission(access, f.submission.id))
            assertIs<AppResult.Success<ExpenseClaimPayments>>(repo.payments(access, f.claim.id))
            assertEquals(6, calls)
        } finally {
            client.close()
        }
    }

    @Test
    fun structuredMfaErrorsDoNotLeakRawServerMessages() = runTest {
        val f = ExpenseDataFixture()
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        """{"status":403,"title":"private detail","detail":"secret","code":"mfa_required"}""",
                        HttpStatusCode.Forbidden,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repo = RemoteExpenseRepository(ApiExpenseDataSource(ExpenseApi(client)))
            val failure = assertIs<AppResult.Failed>(repo.claim(f.access, f.claim.id)).failure
            assertEquals(FailureKind.AccessDenied, failure.kind)
            assertEquals("mfa_required", failure.code)
            assertFalse(failure.message.contains("secret"))
        } finally {
            client.close()
        }
    }

    @Test
    fun anOversizedResponseIsRejectedBeforeDtoPublication() = runTest {
        val f = ExpenseDataFixture()
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        " ".repeat(131_073),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repo = RemoteExpenseRepository(ApiExpenseDataSource(ExpenseApi(client)))
            assertIs<AppResult.Failed>(repo.claim(f.access, f.claim.id))
        } finally {
            client.close()
        }
    }
}
