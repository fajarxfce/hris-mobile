package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationHistoryApi
import dev.fajar.starter.collaboration.data.mappers.collaborationHistoryHead
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationHistoryHeadTest {
    @Test
    fun oneEncodedRoomRequestUsesOnlyTheConfiguredGatewayAndCurrentBearer() = runTest {
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals("gateway.example.test", request.url.host)
                    assertEquals(
                        "/_matrix/client/v3/rooms/!room%2Fpart:example.test/messages",
                        request.url.encodedPath,
                    )
                    assertEquals(setOf("dir", "limit"), request.url.parameters.names())
                    assertEquals("b", request.url.parameters["dir"])
                    assertEquals("1", request.url.parameters["limit"])
                    assertEquals(
                        "Bearer fictional-current-token",
                        request.headers[HttpHeaders.Authorization],
                    )
                    respond(
                        """{"chunk":[{"event_id":"${'$'}latest","content":{"ciphertext":"opaque"}}]}"""
                    )
                },
                "https://application.example.test/api/",
            )
        try {
            val api =
                CollaborationHistoryApi(
                    client,
                    CollaborationSdkEndpoint("https://gateway.example.test/"),
                )
            assertEquals(
                "${'$'}latest",
                collaborationHistoryHead(
                    api.read("fictional-current-token", "!room/part:example.test"),
                    "!room/part:example.test",
                ),
            )
        } finally {
            client.cancelAndClose()
        }
    }

    @Test
    fun emptyPageAndUnsupportedEventTypesSelectNoContentOrOnlyTheOpaqueAnchor() {
        assertNull(collaborationHistoryHead("""{"chunk":[],"start":"opaque"}""", "!room:test"))
        assertEquals(
            "${'$'}state",
            collaborationHistoryHead(
                """{"chunk":[{"event_id":"${'$'}state","type":"m.room.member","room_id":"!room:test"}]}""",
                "!room:test",
            ),
        )
    }

    @Test
    fun malformedOversizeDeepAndForeignResponsesCannotSelectAnAnchor() {
        val invalid =
            listOf(
                "{}",
                "{",
                "[]",
                """{"chunk":null}""",
                """{"chunk":[{},{}]}""",
                """{"chunk":[{"event_id":42}]}""",
                """{"chunk":[{"event_id":"invalid"}]}""",
                """{"chunk":[{"event_id":"${'$'}event","room_id":"!foreign:test"}]}""",
                " ".repeat(262145),
                "[".repeat(17) + "]".repeat(17),
            )
        for (json in invalid) assertFails { collaborationHistoryHead(json, "!room:test") }
    }

    @Test
    fun redirectNeverReceivesTheBearerAndNonOkSuccessCannotPublish() = runTest {
        for (status in listOf(HttpStatusCode.Found, HttpStatusCode.NoContent)) {
            var requests = 0
            val client =
                createHttpClient(
                    MockEngine {
                        requests++
                        respond(
                            "",
                            status,
                            headersOf(HttpHeaders.Location, "https://foreign.example.test/"),
                        )
                    },
                    "https://gateway.example.test/",
                )
            try {
                val api =
                    CollaborationHistoryApi(
                        client,
                        CollaborationSdkEndpoint("https://gateway.example.test/"),
                    )
                assertIs<AppResult.Failed>(safeApiCall { api.read("fictional", "!room:test") })
                assertEquals(1, requests)
            } finally {
                client.cancelAndClose()
            }
        }
    }

    @Test
    fun oversizedStreamOrDeclaredLengthIsRejectedBeforeParsing() = runTest {
        for (declared in listOf(false, true)) {
            val client =
                createHttpClient(
                    MockEngine {
                        respond(
                            "x".repeat(262145),
                            headers =
                                if (declared) headersOf(HttpHeaders.ContentLength, "262145")
                                else headersOf(),
                        )
                    },
                    "https://gateway.example.test/",
                )
            try {
                val api =
                    CollaborationHistoryApi(
                        client,
                        CollaborationSdkEndpoint("https://gateway.example.test/"),
                    )
                assertIs<AppResult.Failed>(safeApiCall { api.read("fictional", "!room:test") })
            } finally {
                client.cancelAndClose()
            }
        }
    }

    @Test
    fun apiPreservesRawHttpRejectionForRepositoryClassification() = runTest {
        val client =
            createHttpClient(
                MockEngine {
                    respond("""{"errcode":"M_UNKNOWN_TOKEN"}""", HttpStatusCode.Unauthorized)
                },
                "https://gateway.example.test/",
            )
        try {
            val api =
                CollaborationHistoryApi(
                    client,
                    CollaborationSdkEndpoint("https://gateway.example.test/"),
                )
            assertEquals(
                401,
                assertFailsWith<ApiResponseException> { api.read("fictional", "!room:test") }
                    .statusCode,
            )
        } finally {
            client.cancelAndClose()
        }
    }
}
