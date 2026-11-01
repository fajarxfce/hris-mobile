package dev.fajar.starter.collaboration.data.api

import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.http.*

/** Borrows the application's public transport. The SDK remains the credential/crypto owner. */
internal class CollaborationHistoryHeadApi(
    private val client: HttpClient,
    private val endpoint: CollaborationSdkEndpoint,
) {
    suspend fun read(accessToken: String, roomId: String): String =
        client
            .prepareGet {
                url {
                    takeFrom(endpoint.baseUrl)
                    appendPathSegments(
                        "_matrix",
                        "client",
                        "v3",
                        "rooms",
                        roomId,
                        "messages",
                        encodeSlash = true,
                    )
                    parameters.append("dir", "b")
                    parameters.append("limit", "1")
                }
                expectSuccess = false
                bearerAuth(accessToken)
            }
            .execute { response ->
                requireApiStatus(response, HttpStatusCode.OK)
                val bytes = readApiBody(response, 262_144)
                try {
                    bytes.decodeToString(throwOnInvalidSequence = true)
                } finally {
                    bytes.fill(0)
                }
            }
}
