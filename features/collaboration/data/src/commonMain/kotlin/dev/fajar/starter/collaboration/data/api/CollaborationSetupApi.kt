@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.api

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class CollaborationSetupApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun read(sessionId: String, companyId: String): CollaborationSetupDto =
        client.requestJson(path(companyId), maximumBytes = 8192) { forSession(sessionId) }

    suspend fun register(
        sessionId: String,
        companyId: String,
        operation: String,
        input: RegisterCollaborationDeviceRequest,
    ): CollaborationReceiptDto =
        client.requestJson(path(companyId), HttpMethod.Post, maximumBytes = 1024) {
            command(sessionId, operation)
            setBody(input)
        }

    suspend fun retry(
        sessionId: String,
        companyId: String,
        operation: String,
        input: RetryCollaborationDeviceRequest,
    ): CollaborationReceiptDto =
        client.requestJson(path(companyId) + "/retry", HttpMethod.Post, maximumBytes = 1024) {
            command(sessionId, operation)
            setBody(input)
        }

    suspend fun bootstrap(
        sessionId: String,
        companyId: String,
        operation: String,
        input: BootstrapCollaborationDeviceRequest,
    ): CollaborationGatewayDto =
        client.requestJson(path(companyId) + "/bootstrap", HttpMethod.Post, maximumBytes = 8192) {
            command(sessionId, operation)
            setBody(input)
        }

    private fun path(companyId: String) =
        "companies/${Uuid.parse(companyId)}/collaboration/native-device"

    private fun HttpRequestBuilder.command(sessionId: String, operation: String) {
        forSession(sessionId)
        header("Idempotency-Key", Uuid.parse(operation).toString())
        contentType(ContentType.Application.Json)
    }
}
