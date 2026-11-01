package dev.fajar.starter.collaboration.data.api

import dev.fajar.starter.collaboration.data.dto.CollaborationHistoryRequest
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.http.*

/** Borrows the application's public transport. The SDK remains the credential/crypto owner. */
internal class CollaborationHistoryApi(
    private val client: HttpClient,
    private val endpoint: CollaborationSdkEndpoint,
) {
    suspend fun read(accessToken: String, roomId: String): String =
        read(accessToken, CollaborationHistoryRequest.Head(roomId))

    suspend fun read(accessToken: String, request: CollaborationHistoryRequest): String =
        client
            .prepareGet {
                url {
                    takeFrom(endpoint.baseUrl)
                    appendPathSegments(
                        "_matrix",
                        "client",
                        "v3",
                        "rooms",
                        request.roomId,
                        "messages",
                        encodeSlash = true,
                    )
                    parameters.append("dir", "b")
                    parameters.append(
                        "limit",
                        if (request is CollaborationHistoryRequest.Head) "1" else "20",
                    )
                    (request as? CollaborationHistoryRequest.Page)?.position?.let {
                        parameters.append("from", it)
                    }
                }
                expectSuccess = false
                bearerAuth(accessToken)
            }
            .execute { response ->
                requireApiStatus(response, HttpStatusCode.OK)
                val bytes =
                    readApiBody(
                        response,
                        if (request is CollaborationHistoryRequest.Head) 262_144 else 2_097_152,
                    )
                try {
                    bytes.decodeToString(throwOnInvalidSequence = true)
                } finally {
                    bytes.fill(0)
                }
            }
}
