package dev.fajar.starter.identity.data.api

import dev.fajar.starter.identity.data.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

/** Proof endpoints use explicit credentials and never the automatic 401 replay interceptor. */
@Single
class MfaApi(@Named(HttpClients.Public) private val client: HttpClient) {
    suspend fun begin(accessToken: String, operationId: String): MfaEnrollmentDto =
        client.requestJson("auth/native/mfa/enrollment", HttpMethod.Post) {
            bearerAuth(accessToken)
            header("Idempotency-Key", operationId)
        }

    suspend fun confirm(
        accessToken: String,
        operationId: String,
        request: MfaConfirmationRequest,
    ): MfaConfirmationDto =
        client.requestJson("auth/native/mfa/enrollment/confirm", HttpMethod.Post) {
            bearerAuth(accessToken)
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun verify(
        accessToken: String,
        operationId: String,
        request: MfaVerificationRequest,
    ): MfaConfirmationDto =
        client.requestJson("auth/native/mfa/verify", HttpMethod.Post) {
            bearerAuth(accessToken)
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
}
