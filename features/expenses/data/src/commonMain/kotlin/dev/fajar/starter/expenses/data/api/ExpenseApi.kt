package dev.fajar.starter.expenses.data.api

import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

@Single
class ExpenseApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
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
    ): List<ExpensePaymentAttemptDto> =
        client.requestJson(
            "companies/$companyId/expenses/claims/$id/payments",
            maximumBytes = 16_384,
        ) {
            forSession(sessionId)
            parameter("scope", "SELF")
        }
}
