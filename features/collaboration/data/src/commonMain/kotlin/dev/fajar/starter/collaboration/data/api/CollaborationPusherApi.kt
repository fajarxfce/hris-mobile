package dev.fajar.starter.collaboration.data.api

import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/** The gateway chooses every pusher field; the native client supplies only its SDK bearer. */
internal class CollaborationPusherApi(
    private val client: HttpClient,
    private val endpoint: CollaborationSdkEndpoint,
) {
    suspend fun activate(accessToken: String) {
        require(accessToken.length in 1..8192 && accessToken.all { it.code in 33..126 })
        client
            .preparePost {
                url {
                    takeFrom(endpoint.baseUrl)
                    appendPathSegments("_matrix", "client", "unstable", "dev.fajar.hris", "pusher")
                }
                expectSuccess = false
                bearerAuth(accessToken)
            }
            .execute { response ->
                requireApiStatus(response, HttpStatusCode.OK)
                val bytes = readApiBody(response, 1024)
                try {
                    val result =
                        Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
                    require(result is JsonObject && result.isEmpty()) {
                        "Invalid pusher acknowledgment."
                    }
                } finally {
                    bytes.fill(0)
                }
            }
    }
}
