package dev.fajar.starter.payroll.data.api

import dev.fajar.starter.network.*
import dev.fajar.starter.payroll.data.dto.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

@Single
class PayrollApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun payslips(
        sessionId: String,
        companyId: String,
        from: String,
        until: String,
        after: String?,
    ): PayslipPageDto =
        client.requestJson("companies/$companyId/payroll/payslips", maximumBytes = 65_536) {
            forSession(sessionId)
            parameter("scope", "SELF")
            parameter("from", from)
            parameter("until", until)
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }

    suspend fun payslip(
        sessionId: String,
        companyId: String,
        id: String,
        revision: Long? = null,
    ): PayslipDto =
        client.requestJson("companies/$companyId/payroll/payslips/$id", maximumBytes = 262_144) {
            forSession(sessionId)
            parameter("scope", "SELF")
            revision?.let { parameter("revision", it) }
        }

    suspend fun payments(sessionId: String, companyId: String, id: String): PayslipPaymentsDto =
        client.requestJson(
            "companies/$companyId/payroll/payslips/$id/payments",
            maximumBytes = 16_384,
        ) {
            forSession(sessionId)
            parameter("scope", "SELF")
        }

    suspend fun inspectPdf(
        sessionId: String,
        companyId: String,
        id: String,
        revision: Long,
        language: String,
    ): PayslipPdfHeadersDto =
        client
            .prepareHead("companies/$companyId/payroll/payslips/$id/pdf") {
                forSession(sessionId)
                parameter("scope", "SELF")
                parameter("revision", revision)
                parameter("language", language)
                header(HttpHeaders.Accept, "application/pdf, application/problem+json")
                expectSuccess = false
            }
            .execute { response ->
                requireApiStatus(response, HttpStatusCode.OK)
                PayslipPdfHeadersDto(
                    response.headers.getAll(HttpHeaders.ETag).orEmpty(),
                    response.headers.getAll(HttpHeaders.ContentLength).orEmpty(),
                    response.headers.getAll(HttpHeaders.ContentType).orEmpty(),
                    response.headers.getAll(HttpHeaders.ContentEncoding).orEmpty(),
                )
            }

    suspend fun downloadPdf(
        sessionId: String,
        companyId: String,
        id: String,
        revision: Long,
        language: String,
        entityTag: String,
        size: Long,
    ): ByteArray =
        client.requestBinaryRange(
            "companies/$companyId/payroll/payslips/$id/pdf",
            entityTag,
            0,
            size,
        ) {
            forSession(sessionId)
            parameter("scope", "SELF")
            parameter("revision", revision)
            parameter("language", language)
            header(HttpHeaders.Accept, "application/pdf, application/problem+json")
            header(HttpHeaders.IfRange, entityTag)
        }
}
