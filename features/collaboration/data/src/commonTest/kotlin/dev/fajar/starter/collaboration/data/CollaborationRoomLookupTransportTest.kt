package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.CollaborationDirectSummary
import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationRoomLookupTransportTest {
    private val summary = """{"conversation":$conversationJson,"peer":$participantJson}"""
    private val room = "!room/+?=:example.test"

    @Test
    fun roomIsEncodedAsOneQueryParameterWithNativeSessionAndBoundedOwnedSummary() = runTest {
        var requests = 0
        withConversations(
            MockEngine { request ->
                requests++
                assertEquals(
                    "/api/v1/companies/${setupOwner.companyId}/collaboration/direct-conversations/summary-by-room",
                    request.url.encodedPath,
                )
                assertEquals(setOf("roomId"), request.url.parameters.names())
                assertEquals(room, request.url.parameters["roomId"])
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(
                    "Bearer fictional-old-native",
                    request.headers[HttpHeaders.Authorization],
                )
                assertNull(request.headers["Idempotency-Key"])
                respond(summary, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { repo ->
            val value =
                assertIs<AppResult.Success<CollaborationDirectSummary>>(
                        repo.summaryByRoom(setupOwner, room)
                    )
                    .value
            assertEquals(CONVERSATION_ID, value.conversation.id)
            assertEquals(PEER_ID, value.peer.accountId)
            assertEquals(1, requests)
        }
    }

    @Test
    fun malformedRoomOrForeignAudienceNeverReachesTransport() = runTest {
        withConversations(MockEngine { error("No invalid transport") }) { repo ->
            for (id in listOf("", "!", "alias", "!space room", "!line\n", "!" + "x".repeat(255))) {
                assertIs<AppResult.Failed>(repo.summaryByRoom(setupOwner, id))
            }
            assertIs<AppResult.Failed>(
                repo.summaryByRoom(setupOwner.copy(audience = "https://elsewhere.test/"), room)
            )
        }
    }

    @Test
    fun substitutedOwnerPeerAndOversizedPayloadCannotBecomeOwnedFacts() = runTest {
        for (body in
            listOf(
                summary.replace(setupOwner.companyId, SETUP_DEVICE),
                summary.replace(setupOwner.accountId, SETUP_DEVICE),
                """{"conversation":$conversationJson,"peer":${participantJson.replace(PEER_ID, setupOwner.accountId)}}""",
                summary + " ".repeat(4096),
            )) withConversations(
            MockEngine {
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.summaryByRoom(setupOwner, room))
        }
    }

    @Test
    fun currentAccessDenialRetainsItsClassification() = runTest {
        withConversations(
            MockEngine {
                respond(
                    """{"code":"permission_denied","title":"Private provider detail"}""",
                    HttpStatusCode.Forbidden,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
            }
        ) { repo ->
            val failed = assertIs<AppResult.Failed>(repo.summaryByRoom(setupOwner, room)).failure
            assertEquals(FailureKind.AccessDenied, failed.kind)
            assertEquals("permission_denied", failed.code)
            assertFalse(failed.message.contains("Private provider detail"))
        }
    }

    @Test
    fun callerCancellationStopsPendingHttpWithoutPublishingSuccess() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var published = false
        withConversations(
            MockEngine {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        ) { repo ->
            val task = async { repo.summaryByRoom(setupOwner, room).also { published = true } }
            entered.await()
            task.cancelAndJoin()
            // Ktor's engine request belongs to the HTTP client, not the waiting test task.
            // Await that separate completion signal instead of racing an unsynchronized flag.
            withContext(Dispatchers.Default) { withTimeout(5_000) { stopped.await() } }
            assertTrue(task.isCancelled)
            assertFalse(published)
        }
    }
}
