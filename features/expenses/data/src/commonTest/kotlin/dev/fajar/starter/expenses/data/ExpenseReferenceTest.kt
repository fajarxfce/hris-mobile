package dev.fajar.starter.expenses.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentRevisionStatus
import dev.fajar.starter.expenses.data.api.ExpenseApi
import dev.fajar.starter.expenses.data.datasources.ApiExpenseDataSource
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.repositories.RemoteExpensePreparationRepository
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.network.RequestSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class ExpenseReferenceTest {
    @Test
    fun unavailableAndArchivedReferencesRetainOnlyTheirAcceptedMetadata() = runTest {
        val f = ExpenseDataFixture()
        val query =
            ExpenseReferenceQuery(
                f.employee.id,
                setOf(f.line.categoryId),
                setOf(f.employee.id),
                setOf(f.receipt.revisionId),
            )
        val repo = RemoteExpensePreparationRepository(f)
        val values =
            assertIs<AppResult.Success<ExpenseReferenceValues>>(repo.references(f.access, query))
                .value
        assertEquals(false, values.categories.single().active)
        assertEquals("Travel", values.categories.single().name)
        assertNull(values.receipts.single().documentId)
        for (status in DocumentRevisionStatus.entries) {
            f.referenceValues =
                ExpenseReferenceValuesDto(
                    f.employee.id,
                    listOf(ExpenseLabelReferenceDto(f.line.categoryId, null, null, null)),
                    listOf(ExpenseLabelReferenceDto(f.employee.id, null, null, null)),
                    listOf(
                        ExpenseReceiptReferenceDto(
                            f.receipt.revisionId,
                            f.employee.id,
                            "Receipt.pdf",
                            "application/pdf",
                            1024,
                            status.name,
                        )
                    ),
                )
            val result =
                assertIs<AppResult.Success<ExpenseReferenceValues>>(
                        repo.references(f.access, query)
                    )
                    .value
            assertEquals(status, result.receipts.single().status)
            assertNull(result.categories.single().name)
        }
    }

    @Test
    fun partialMetadataUnknownStatusScopeMismatchAndOversizedSetsFailAtTheBoundary() = runTest {
        val f = ExpenseDataFixture()
        val category = ExpenseLabelReferenceDto(f.line.categoryId, "TRAVEL", "Travel", true)
        val receipt =
            ExpenseReceiptReferenceDto(
                f.receipt.revisionId,
                f.employee.id,
                "Receipt.pdf",
                "application/pdf",
                1024,
                "READY",
            )
        val valid =
            ExpenseReferenceValuesDto(f.employee.id, listOf(category), emptyList(), listOf(receipt))
        val query =
            ExpenseReferenceQuery(
                f.employee.id,
                setOf(category.id),
                receiptRevisionIds = setOf(receipt.revisionId),
            )
        val invalid =
            listOf(
                valid.copy(employmentId = f.summary.id),
                valid.copy(categories = emptyList()),
                valid.copy(categories = List(21) { category }),
                valid.copy(categories = listOf(category.copy(code = null))),
                valid.copy(categories = listOf(category.copy(name = ""))),
                valid.copy(categories = listOf(category.copy(active = null))),
                valid.copy(categories = listOf(category.copy(id = "../category"))),
                valid.copy(receipts = listOf(receipt.copy(documentId = null))),
                valid.copy(receipts = listOf(receipt.copy(fileName = "x".repeat(513)))),
                valid.copy(receipts = listOf(receipt.copy(size = 67_108_865))),
                valid.copy(receipts = listOf(receipt.copy(status = "UNKNOWN"))),
                valid.copy(receipts = listOf(receipt, receipt)),
            )
        val repo = RemoteExpensePreparationRepository(f)
        for (value in invalid) {
            f.referenceValues = value
            assertIs<AppResult.Failed>(repo.references(f.access, query))
        }
    }

    @Test
    fun lookupUsesTheOwnedRouteAndAdmittedSessionAndOmitsEmptyParameters() = runTest {
        val f = ExpenseDataFixture()
        var calls = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    assertEquals(
                        "/api/v1/companies/${f.access.companyId}/expenses/me/employments/${f.employee.id}/draft-references",
                        request.url.encodedPath,
                    )
                    assertEquals(f.line.categoryId, request.url.parameters["categoryIds"])
                    assertNull(request.url.parameters["costCenterIds"])
                    assertNull(request.url.parameters["receiptRevisionIds"])
                    respond(
                        Json.encodeToString(
                            ExpenseReferenceValuesDto(
                                f.employee.id,
                                listOf(
                                    ExpenseLabelReferenceDto(f.line.categoryId, null, null, null)
                                ),
                                emptyList(),
                                emptyList(),
                            )
                        ),
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
                repo.references(
                    f.access,
                    ExpenseReferenceQuery(f.employee.id, setOf(f.line.categoryId)),
                )
            )
            assertIs<AppResult.Failed>(repo.references(f.access, ExpenseReferenceQuery("../owner")))
            assertEquals(1, calls)
        } finally {
            client.close()
        }
    }

    @Test
    fun oversizedLookupAndStructuredMfaFailuresStayInsideTheBoundary() = runTest {
        val f = ExpenseDataFixture()
        for (denied in listOf(false, true)) {
            val client =
                HttpClient(
                    MockEngine {
                        if (denied)
                            respond(
                                """{"status":403,"code":"mfa_required","detail":"private"}""",
                                HttpStatusCode.Forbidden,
                                headersOf(HttpHeaders.ContentType, "application/problem+json"),
                            )
                        else
                            respond(
                                " ".repeat(262_145),
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
                val failure =
                    assertIs<AppResult.Failed>(
                            repo.references(f.access, ExpenseReferenceQuery(f.employee.id))
                        )
                        .failure
                if (denied) {
                    assertEquals(FailureKind.AccessDenied, failure.kind)
                    assertEquals("mfa_required", failure.code)
                }
                assertFalse(failure.message.contains("private"))
            } finally {
                client.close()
            }
        }
    }
}
