package dev.fajar.starter.expenses.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.data.api.ExpenseApi
import dev.fajar.starter.expenses.data.datasources.ApiExpenseReceiptDataSource
import dev.fajar.starter.expenses.data.repositories.RemoteExpenseReceiptRepository
import dev.fajar.starter.expenses.domain.entities.ExpenseReceiptChunk
import dev.fajar.starter.expenses.domain.policies.EXPENSE_RECEIPT_CHUNK_BYTES
import dev.fajar.starter.network.RequestSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class ExpenseReceiptTransportTest {
    @Test
    fun metadataAndEveryContentRangePinEmployeeSelfScopeAndImmutableEvidence() = runTest {
        val f = ExpenseReceiptDataFixture()
        var calls = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals(
                        "/api/v1/companies/${f.access.companyId}/expenses/submissions/${f.submissionId}/receipts/${f.receipt.id}/content",
                        request.url.encodedPath,
                    )
                    assertEquals("SELF", request.url.parameters["scope"])
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    val headers =
                        HeadersBuilder().apply {
                            append(HttpHeaders.ETag, f.tag)
                            append(HttpHeaders.ContentType, f.receipt.mediaType)
                        }
                    assertEquals(HttpMethod.Get, request.method)
                    if (calls == 1) {
                        assertEquals("bytes=0-0", request.headers[HttpHeaders.Range])
                        assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
                        headers.append(HttpHeaders.ContentLength, "1")
                        headers.append(HttpHeaders.ContentRange, "bytes 0-0/${f.receipt.size}")
                        respond(f.body.copyOf(1), HttpStatusCode.PartialContent, headers.build())
                    } else {
                        assertEquals(HttpMethod.Get, request.method)
                        assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
                        assertEquals(f.tag, request.headers[HttpHeaders.IfRange])
                        val start = if (calls == 2) 0 else EXPENSE_RECEIPT_CHUNK_BYTES
                        val end = minOf(start + EXPENSE_RECEIPT_CHUNK_BYTES, f.body.size) - 1
                        assertEquals("bytes=$start-$end", request.headers[HttpHeaders.Range])
                        headers.append(HttpHeaders.ContentLength, (end - start + 1).toString())
                        headers.append(
                            HttpHeaders.ContentRange,
                            "bytes $start-$end/${f.receipt.size}",
                        )
                        respond(
                            f.body.copyOfRange(start, end + 1),
                            HttpStatusCode.PartialContent,
                            headers.build(),
                        )
                    }
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repo =
                RemoteExpenseReceiptRepository(ApiExpenseReceiptDataSource(ExpenseApi(client)))
            val access = f.access.copy(permissions = setOf("expenses.manage"))
            assertEquals(AppResult.Success(Unit), repo.inspect(access, f.submissionId, f.receipt))
            var bytes = 0L
            repo.chunks(access, f.submissionId, f.receipt).collect {
                bytes += assertIs<AppResult.Success<ExpenseReceiptChunk>>(it).value.bytes.size
            }
            assertEquals(f.receipt.size, bytes)
            assertEquals(3, calls)
        } finally {
            client.close()
        }
    }

    @Test
    fun changedRangesAndNonPartialResponsesCannotBecomeAReceiptChunk() = runTest {
        for (case in 0..3) {
            val f = ExpenseReceiptDataFixture()
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            f.body.copyOf(EXPENSE_RECEIPT_CHUNK_BYTES),
                            if (case == 0) HttpStatusCode.OK else HttpStatusCode.PartialContent,
                            headersOf(
                                HttpHeaders.ETag to listOf(if (case == 1) "\"changed\"" else f.tag),
                                HttpHeaders.ContentRange to
                                    listOf(
                                        if (case == 2) "bytes 1-1048576/${f.receipt.size}"
                                        else "bytes 0-1048575/${f.receipt.size}"
                                    ),
                                HttpHeaders.ContentEncoding to
                                    listOf(if (case == 3) "gzip" else "identity"),
                            ),
                        )
                    }
                ) {
                    defaultRequest { url("https://hris.example.test/api/v1/") }
                }
            try {
                val repo =
                    RemoteExpenseReceiptRepository(ApiExpenseReceiptDataSource(ExpenseApi(client)))
                assertIs<AppResult.Failed>(
                    repo.chunks(f.access, f.submissionId, f.receipt).single()
                )
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun protectedContentFailuresRetainLocalizableCodesWithoutPrivateDetails() = runTest {
        val f = ExpenseReceiptDataFixture()
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        """{"status":403,"code":"mfa_required","detail":"private receipt"}""",
                        HttpStatusCode.Forbidden,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repo =
                RemoteExpenseReceiptRepository(ApiExpenseReceiptDataSource(ExpenseApi(client)))
            val inspectionFailure =
                assertIs<AppResult.Failed>(repo.inspect(f.access, f.submissionId, f.receipt))
                    .failure
            assertEquals("mfa_required", inspectionFailure.code)
            assertEquals(FailureKind.AccessDenied, inspectionFailure.kind)
            assertFalse(inspectionFailure.message.contains("private receipt"))
            val failure =
                assertIs<AppResult.Failed>(
                        repo.chunks(f.access, f.submissionId, f.receipt).single()
                    )
                    .failure
            assertEquals("mfa_required", failure.code)
            assertEquals(FailureKind.AccessDenied, failure.kind)
            assertFalse(failure.message.contains("private receipt"))
        } finally {
            client.close()
        }
    }
}
