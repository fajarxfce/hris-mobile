package dev.fajar.starter.expenses.data.api

import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

@Single
class ExpenseApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun inspectReceipt(
        sessionId: String,
        companyId: String,
        submissionId: String,
        revisionId: String,
    ): ExpenseReceiptHeadersDto =
        client
            .prepareGet(
                "companies/$companyId/expenses/submissions/$submissionId/receipts/$revisionId/content"
            ) {
                forSession(sessionId)
                parameter("scope", "SELF")
                // A one-byte GET retains structured problem bodies, including MFA codes.
                header(HttpHeaders.Range, "bytes=0-0")
                header(HttpHeaders.AcceptEncoding, "identity")
                header(
                    HttpHeaders.Accept,
                    "application/pdf, image/jpeg, image/png, application/problem+json",
                )
                expectSuccess = false
            }
            .execute { response ->
                requireApiStatus(response, HttpStatusCode.PartialContent)
                val probe = readApiBody(response, 1)
                try {
                    require(probe.size == 1)
                    ExpenseReceiptHeadersDto(
                        response.headers.getAll(HttpHeaders.ETag).orEmpty(),
                        response.headers.getAll(HttpHeaders.ContentLength).orEmpty(),
                        response.headers.getAll(HttpHeaders.ContentType).orEmpty(),
                        response.headers.getAll(HttpHeaders.ContentEncoding).orEmpty(),
                        response.headers.getAll(HttpHeaders.ContentRange).orEmpty(),
                    )
                } finally {
                    probe.fill(0)
                }
            }

    suspend fun receiptChunk(
        sessionId: String,
        companyId: String,
        submissionId: String,
        revisionId: String,
        entityTag: String,
        size: Long,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray =
        client.requestBinaryRange(
            "companies/$companyId/expenses/submissions/$submissionId/receipts/$revisionId/content",
            entityTag,
            offset,
            size,
            maximumBytes,
        ) {
            forSession(sessionId)
            parameter("scope", "SELF")
            header(
                HttpHeaders.Accept,
                "application/pdf, image/jpeg, image/png, application/problem+json",
            )
            header(HttpHeaders.IfRange, entityTag)
        }

    suspend fun references(
        sessionId: String,
        companyId: String,
        employeeId: String,
        categoryIds: List<String>,
        costCenterIds: List<String>,
        receiptRevisionIds: List<String>,
    ): ExpenseReferenceValuesDto =
        client.requestJson(
            "companies/$companyId/expenses/me/employments/$employeeId/draft-references",
            maximumBytes = 262_144,
        ) {
            forSession(sessionId)
            if (categoryIds.isNotEmpty()) parameter("categoryIds", categoryIds.joinToString(","))
            if (costCenterIds.isNotEmpty())
                parameter("costCenterIds", costCenterIds.joinToString(","))
            if (receiptRevisionIds.isNotEmpty())
                parameter("receiptRevisionIds", receiptRevisionIds.joinToString(","))
        }

    suspend fun saveDraft(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseSaveDraftRequest,
    ): ExpenseCommandReceiptDto =
        client.requestJson("companies/$companyId/expenses/claims/$id/draft", maximumBytes = 4_096) {
            forSession(sessionId)
            method = HttpMethod.Put
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun submit(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseSubmitRequest,
    ): ExpenseCommandReceiptDto =
        client.requestJson(
            "companies/$companyId/expenses/claims/$id/submit",
            maximumBytes = 4_096,
        ) {
            forSession(sessionId)
            method = HttpMethod.Post
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun cancel(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseActionRequest,
    ): ExpenseCommandReceiptDto =
        client.requestJson(
            "companies/$companyId/expenses/claims/$id/cancel",
            maximumBytes = 4_096,
        ) {
            forSession(sessionId)
            method = HttpMethod.Post
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun withdraw(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseActionRequest,
    ): ExpenseCommandReceiptDto =
        client.requestJson(
            "companies/$companyId/expenses/submissions/$id/withdraw",
            maximumBytes = 4_096,
        ) {
            forSession(sessionId)
            method = HttpMethod.Post
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun categories(
        sessionId: String,
        companyId: String,
        asOf: String,
        after: String?,
    ): ExpensePageDto<ExpenseCategoryDto> =
        client.requestJson("companies/$companyId/expenses/categories", maximumBytes = 65_536) {
            forSession(sessionId)
            parameter("asOf", asOf)
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }

    suspend fun costCenters(
        sessionId: String,
        companyId: String,
        query: String,
        after: String?,
    ): ExpensePageDto<ExpenseCostCenterDto> =
        client.requestJson("companies/$companyId/expenses/cost-centers", maximumBytes = 16_384) {
            forSession(sessionId)
            parameter("query", query)
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }

    suspend fun directory(
        sessionId: String,
        companyId: String,
        after: String?,
    ): ExpenseDirectoryDto =
        client.requestJson("companies/$companyId/expenses/me/employments", maximumBytes = 16_384) {
            forSession(sessionId)
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }

    suspend fun claims(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
        status: String?,
        after: String?,
    ): ExpensePageDto<ExpenseSummaryDto> =
        client.requestJson("companies/$companyId/expenses/claims", maximumBytes = 65_536) {
            forSession(sessionId)
            parameter("scope", "SELF")
            parameter("employmentId", employeeId)
            parameter("from", from)
            parameter("until", until)
            parameter("limit", 20)
            status?.let { parameter("status", it) }
            after?.let { parameter("after", it) }
        }

    suspend fun claim(sessionId: String, companyId: String, id: String): ExpenseClaimDto =
        client.requestJson("companies/$companyId/expenses/claims/$id", maximumBytes = 131_072) {
            forSession(sessionId)
            parameter("scope", "SELF")
        }

    suspend fun record(
        sessionId: String,
        companyId: String,
        id: String,
        historyAfter: Long?,
    ): ExpenseClaimRecordDto =
        client.requestJson(
            "companies/$companyId/expenses/claims/$id/record",
            maximumBytes = 1_048_576,
        ) {
            forSession(sessionId)
            parameter("scope", "SELF")
            parameter("historyLimit", 20)
            historyAfter?.let { parameter("historyAfter", it) }
        }

    suspend fun history(
        sessionId: String,
        companyId: String,
        id: String,
        after: Long?,
    ): ExpensePageDto<ExpenseChangeDto> =
        client.requestJson(
            "companies/$companyId/expenses/claims/$id/history",
            maximumBytes = 131_072,
        ) {
            forSession(sessionId)
            parameter("scope", "SELF")
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }

    suspend fun submission(sessionId: String, companyId: String, id: String): ExpenseSubmissionDto =
        client.requestJson(
            "companies/$companyId/expenses/submissions/$id",
            maximumBytes = 524_288,
        ) {
            forSession(sessionId)
            parameter("scope", "SELF")
        }

    suspend fun payments(
        sessionId: String,
        companyId: String,
        id: String,
    ): ExpenseClaimPaymentsDto =
        client.requestJson(
            "companies/$companyId/expenses/claims/$id/payment-progress",
            maximumBytes = 16_384,
        ) {
            forSession(sessionId)
            parameter("scope", "SELF")
        }
}
