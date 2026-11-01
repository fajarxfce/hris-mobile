package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationHistoryApi
import dev.fajar.starter.collaboration.data.dto.CollaborationHistoryRequest
import dev.fajar.starter.collaboration.data.mappers.collaborationProviderPage
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CollaborationProviderPageTest {
    private val room = "!room:test"

    private fun event(id: String = "\$event", timestamp: Long = 30) = buildJsonObject {
        put("event_id", id)
        put("sender", "@fictional:test")
        put("origin_server_ts", timestamp)
        put("type", "m.room.encrypted")
        put("room_id", room)
        putJsonObject("content") { put("ciphertext", "opaque fixture payload") }
    }

    private fun page(
        events: List<JsonObject>,
        start: String = "opaque-start",
        next: String? = "opaque-next",
    ) =
        buildJsonObject {
                put("start", start)
                next?.let { put("end", it) }
                put("chunk", JsonArray(events))
            }
            .toString()

    @Test
    fun providerOrderAndOpaquePositionsSurviveWithoutReturningCiphertext() {
        val result =
            collaborationProviderPage(
                page(listOf(event("\$later", 10), event("\$earlier", 90))),
                room,
                null,
            )
        assertEquals(listOf("\$later", "\$earlier"), result.events.map { it.eventId })
        assertEquals(listOf(10L, 90L), result.events.map { it.timestamp })
        assertEquals("opaque-start", result.start)
        assertEquals("opaque-next", result.next)
        assertFalse(result.toString().contains("opaque"))
        assertTrue(result.events.all { !it.toString().contains("fixture") })
    }

    @Test
    fun emptyAdvancingPagesRemainTraversableAndOnlyAbsentOrNullEndIsTerminal() {
        assertEquals("opaque-next", collaborationProviderPage(page(emptyList()), room, null).next)
        assertNull(collaborationProviderPage(page(emptyList(), next = null), room, null).next)
        assertNull(
            collaborationProviderPage("""{"start":"opaque","end":null,"chunk":[]}""", room, null)
                .next
        )
    }

    @Test
    fun repeatedPositionsCannotMasqueradeAsProgressEvenWithVisibleEvents() {
        for (events in listOf(emptyList(), listOf(event()))) {
            assertFails {
                collaborationProviderPage(page(events, next = "opaque-start"), room, null)
            }
            assertFails {
                collaborationProviderPage(page(events, next = "requested"), room, "requested")
            }
        }
    }

    @Test
    fun malformedForeignDuplicateAndOversizedEventBatchesFailWholeMapping() {
        val original = event()
        val invalid =
            listOf(
                original + ("event_id" to JsonPrimitive("invalid")),
                original + ("sender" to JsonPrimitive("foreign")),
                original + ("room_id" to JsonPrimitive("!foreign:test")),
                original + ("type" to JsonPrimitive("")),
                original + ("origin_server_ts" to JsonPrimitive("30")),
                original + ("origin_server_ts" to JsonPrimitive(-1)),
                original + ("origin_server_ts" to JsonPrimitive(9_007_199_254_740_992L)),
            )
        for (event in invalid) assertFails {
            collaborationProviderPage(page(listOf(JsonObject(event))), room, null)
        }
        assertFails { collaborationProviderPage(page(listOf(original, original)), room, null) }
        assertFails {
            collaborationProviderPage(page((1..21).map { event("\$event-$it") }), room, null)
        }
    }

    @Test
    fun malformedPositionShapeAndExcessiveNestingCannotPublish() {
        for (json in
            listOf(
                "{}",
                "[]",
                """{"start":3,"chunk":[]}""",
                """{"start":"","chunk":[]}""",
                """{"start":"ok","end":false,"chunk":[]}""",
                page(emptyList(), start = "x".repeat(4097)),
                page(emptyList(), next = "bad\nposition"),
                "[".repeat(17) + "]".repeat(17),
                " ".repeat(2_097_153),
            )) {
            assertFails { collaborationProviderPage(json, room, null) }
        }
    }

    @Test
    fun aPositionedPageUsesOneEncodedGatewayRequestWithTwentyEventLimit() = runTest {
        val position = "opaque+/=?&escaped"
        var count = 0
        val http =
            createHttpClient(
                MockEngine { request ->
                    count++
                    assertEquals("gateway.example.test", request.url.host)
                    assertEquals(position, request.url.parameters["from"])
                    assertEquals("b", request.url.parameters["dir"])
                    assertEquals("20", request.url.parameters["limit"])
                    assertEquals(setOf("from", "dir", "limit"), request.url.parameters.names())
                    assertEquals(
                        "Bearer fictional-current",
                        request.headers[HttpHeaders.Authorization],
                    )
                    respond(page(emptyList()))
                },
                "https://application.example.test/api/",
            )
        try {
            val api =
                CollaborationHistoryApi(
                    http,
                    CollaborationSdkEndpoint("https://gateway.example.test/"),
                )
            val raw =
                api.read("fictional-current", CollaborationHistoryRequest.Page(room, position))
            assertEquals("opaque-next", collaborationProviderPage(raw, room, position).next)
            assertEquals(1, count)
        } finally {
            http.cancelAndClose()
        }
    }

    @Test
    fun thePageBodyHasItsOwnFiniteLimit() = runTest {
        val http =
            createHttpClient(
                MockEngine { respond("x".repeat(2_097_153)) },
                "https://gateway.example.test/",
            )
        try {
            val api =
                CollaborationHistoryApi(
                    http,
                    CollaborationSdkEndpoint("https://gateway.example.test/"),
                )
            assertIs<AppResult.Failed>(
                safeApiCall { api.read("fictional", CollaborationHistoryRequest.Page(room, null)) }
            )
        } finally {
            http.cancelAndClose()
        }
    }
}
