package dev.fajar.starter.payroll.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.RequestSession
import dev.fajar.starter.payroll.data.api.PayrollApi
import dev.fajar.starter.payroll.data.datasources.ApiPayrollDataSource
import dev.fajar.starter.payroll.data.repositories.RemotePayslipRepository
import dev.fajar.starter.payroll.domain.entities.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class PayslipTransportTest {
    @Test
    fun historyPinsTheRequestedRevisionAndNeverFallsBackToTheCurrentStatement() = runTest {
        val f = PayslipDataFixture()
        var calls = 0
        var mode = "valid"
        val client =
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals(
                        "/api/v1/companies/${f.access.companyId}/payroll/payslips/${f.summary.id}",
                        request.url.encodedPath,
                    )
                    assertEquals("SELF", request.url.parameters["scope"])
                    assertEquals(listOf("1"), request.url.parameters.getAll("revision"))
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    when (mode) {
                        "missing" ->
                            respond(
                                """{"type":"about:blank","title":"Unavailable","status":404,"code":"payroll_payslip_not_found"}""",
                                HttpStatusCode.NotFound,
                                headersOf(HttpHeaders.ContentType, "application/problem+json"),
                            )
                        else ->
                            respond(
                                Json.encodeToString(
                                    f.detail.copy(
                                        summary =
                                            f.summary.copy(version = if (mode == "valid") 1 else 3)
                                    )
                                ),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                    }
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repository = RemotePayslipRepository(ApiPayrollDataSource(PayrollApi(client)))
            assertEquals(
                1L,
                assertIs<AppResult.Success<Payslip>>(repository.revision(f.access, f.summary.id, 1))
                    .value
                    .summary
                    .version,
            )
            mode = "mismatched"
            assertIs<AppResult.Failed>(repository.revision(f.access, f.summary.id, 1))
            mode = "missing"
            assertEquals(
                "payroll_payslip_not_found",
                assertIs<AppResult.Failed>(repository.revision(f.access, f.summary.id, 1))
                    .failure
                    .code,
            )
            assertEquals(3, calls)
        } finally {
            client.close()
        }
    }

    @Test
    fun allReadsPinSelfScopeAndPdfRequiresAnExactConditionalRange() = runTest {
        val f = PayslipDataFixture(pdfRevision = 3)
        var calls = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals("SELF", request.url.parameters["scope"])
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    val path = "/api/v1/companies/${f.access.companyId}/payroll/payslips"
                    when (request.url.encodedPath) {
                        path -> {
                            assertEquals("2026-01", request.url.parameters["from"])
                            assertEquals("2026-12", request.url.parameters["until"])
                            assertEquals("20", request.url.parameters["limit"])
                            assertNull(request.url.parameters["after"])
                            respond(
                                Json.encodeToString(f.page),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        "$path/${f.summary.id}" ->
                            respond(
                                Json.encodeToString(f.detail),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        "$path/${f.summary.id}/payments" ->
                            respond(
                                Json.encodeToString(f.payments),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        "$path/${f.summary.id}/pdf" -> {
                            assertEquals("ID", request.url.parameters["language"])
                            assertEquals(
                                f.metadata.revision.toString(),
                                request.url.parameters["revision"],
                            )
                            val headers =
                                Headers.build {
                                    append(HttpHeaders.ContentType, "application/pdf")
                                    append(HttpHeaders.ETag, f.metadata.entityTag)
                                    append(HttpHeaders.ContentLength, f.bytes.size.toString())
                                    if (request.method == HttpMethod.Get)
                                        append(
                                            HttpHeaders.ContentRange,
                                            "bytes 0-${f.bytes.lastIndex}/${f.bytes.size}",
                                        )
                                }
                            if (request.method == HttpMethod.Head)
                                respond(ByteArray(0), HttpStatusCode.OK, headers)
                            else {
                                assertEquals(HttpMethod.Get, request.method)
                                assertEquals(
                                    "bytes=0-${f.bytes.lastIndex}",
                                    request.headers[HttpHeaders.Range],
                                )
                                assertEquals(
                                    f.metadata.entityTag,
                                    request.headers[HttpHeaders.IfRange],
                                )
                                assertEquals(
                                    "identity",
                                    request.headers[HttpHeaders.AcceptEncoding],
                                )
                                respond(f.bytes, HttpStatusCode.PartialContent, headers)
                            }
                        }
                        else -> error("Unexpected test path")
                    }
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repo = RemotePayslipRepository(ApiPayrollDataSource(PayrollApi(client)))
            // Finance permissions still cannot broaden an employee-page request.
            val access = f.access.copy(permissions = setOf("payroll.read"))
            assertIs<AppResult.Success<PayslipPage>>(repo.list(access, PayslipSelection(2026)))
            assertIs<AppResult.Success<Payslip>>(repo.details(access, f.summary.id))
            assertIs<AppResult.Success<PayslipPayments>>(repo.payments(access, f.summary.id))
            assertEquals(
                AppResult.Success(f.metadata),
                repo.inspect(access, f.summary.id, f.metadata.revision, PayslipDocumentLanguage.ID),
            )
            val bytes =
                assertIs<AppResult.Success<ByteArray>>(repo.download(access, f.metadata)).value
            assertContentEquals(f.bytes, bytes)
            bytes.fill(0)
            assertEquals(5, calls)
        } finally {
            client.close()
        }
    }

    @Test
    fun authoritativeErrorsRemainStructuredAndDoNotExposeServerDiagnostics() = runTest {
        val f = PayslipDataFixture()
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        """{"type":"about:blank","title":"private detail","status":403,"detail":"secret","code":"mfa_required"}""",
                        HttpStatusCode.Forbidden,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repo = RemotePayslipRepository(ApiPayrollDataSource(PayrollApi(client)))
            val result = assertIs<AppResult.Failed>(repo.list(f.access, PayslipSelection(2026)))
            assertEquals(FailureKind.AccessDenied, result.failure.kind)
            assertEquals("mfa_required", result.failure.code)
            assertFalse(result.failure.message.contains("secret"))
        } finally {
            client.close()
        }
    }
}
