package dev.fajar.starter.expenses.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.data.api.ExpenseApi
import dev.fajar.starter.expenses.data.datasources.ApiExpenseDataSource
import dev.fajar.starter.expenses.data.repositories.RemoteExpensePreparationRepository
import dev.fajar.starter.network.RequestSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

class ExpensePreparationTransportTest {
    @Test
    fun queriesUseTheAdmittedSessionCompanyDateAndBoundedIssuedCursors() = runTest {
        val f = ExpenseDataFixture()
        var calls = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    assertEquals("20", request.url.parameters["limit"])
                    val body =
                        when (request.url.encodedPath) {
                            "/api/v1/companies/${f.access.companyId}/expenses/categories" -> {
                                assertEquals("2026-10-13", request.url.parameters["asOf"])
                                assertEquals("PRIOR", request.url.parameters["after"])
                                Json.encodeToString(f.categories)
                            }
                            "/api/v1/companies/${f.access.companyId}/expenses/cost-centers" -> {
                                assertEquals("50% & team", request.url.parameters["query"])
                                assertEquals("COST_CENTER:CC00", request.url.parameters["after"])
                                Json.encodeToString(f.costCenters)
                            }
                            else -> error("Unexpected preparation route")
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
            val repo = RemoteExpensePreparationRepository(ApiExpenseDataSource(ExpenseApi(client)))
            assertIs<AppResult.Success<*>>(
                repo.categories(f.access, LocalDate(2026, 10, 13), "PRIOR")
            )
            assertIs<AppResult.Success<*>>(
                repo.costCenters(f.access, "  50% & team  ", "COST_CENTER:CC00")
            )
            assertEquals(2, calls)
        } finally {
            client.close()
        }
    }

    @Test
    fun oversizedResponsesAndStructuredAccessFailuresRemainBounded() = runTest {
        val f = ExpenseDataFixture()
        for (categories in listOf(false, true)) for (denied in listOf(false, true)) {
            val maximum = if (categories) 65_536 else 16_384
            val client =
                HttpClient(
                    MockEngine {
                        if (denied)
                            respond(
                                """{"status":403,"title":"private detail","detail":"secret","code":"mfa_required"}""",
                                HttpStatusCode.Forbidden,
                                headersOf(HttpHeaders.ContentType, "application/problem+json"),
                            )
                        else
                            respond(
                                " ".repeat(maximum + 1),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                    }
                ) {
                    defaultRequest { url("https://hris.example.test/api/v1/") }
                }
            try {
                val repo =
                    RemoteExpensePreparationRepository(ApiExpenseDataSource(ExpenseApi(client)))
                val result =
                    if (categories) repo.categories(f.access, LocalDate(2026, 10, 13), null)
                    else repo.costCenters(f.access, "", null)
                val failure = assertIs<AppResult.Failed>(result).failure
                if (denied) {
                    assertEquals(FailureKind.AccessDenied, failure.kind)
                    assertEquals("mfa_required", failure.code)
                }
                assertFalse(failure.message.contains("secret"))
            } finally {
                client.close()
            }
        }
    }
}
