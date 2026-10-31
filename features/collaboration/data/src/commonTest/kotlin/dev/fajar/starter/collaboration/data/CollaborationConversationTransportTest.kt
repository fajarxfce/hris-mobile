package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationConversationApi
import dev.fajar.starter.collaboration.data.datasources.ApiCollaborationConversationRemoteSource
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationConversationRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

internal const val CONVERSATION_ID = "88888888-8888-4888-8888-888888888888"
internal const val PEER_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
internal val conversationJson =
    """{"id":"$CONVERSATION_ID","companyId":"${setupOwner.companyId}","firstAccountId":"${setupOwner.accountId}","secondAccountId":"$PEER_ID","createdBy":"$PEER_ID","createdAt":"2026-10-31T00:00:00Z","version":0}"""
internal val participantJson = """{"accountId":"$PEER_ID","displayName":"Fictional coworker"}"""
internal val roomJson =
    """{"conversationId":"$CONVERSATION_ID","version":0,"state":"READY","matrixRoomId":"!opaque-v12-room","creatorAccountId":"$PEER_ID","retryable":false,"retryAt":null}"""

internal suspend fun withConversations(
    engine: MockEngine,
    action: suspend (RemoteCollaborationConversationRepository) -> Unit,
) {
    val client =
        createHttpClient(engine, HttpClientSettings(SETUP_BASE)) {
            install(SessionAuthentication) {
                origin = Url(SETUP_BASE)
                acquire = { session, rejected ->
                    assertEquals(setupOwner.sessionId, session)
                    AppResult.Success(
                        if (rejected == null) "fictional-old-native" else "fictional-renewed-native"
                    )
                }
            }
        }
    try {
        action(
            RemoteCollaborationConversationRepository(
                ApiCollaborationConversationRemoteSource(CollaborationConversationApi(client)),
                IdentityServer(SETUP_BASE, "Fictional Android"),
            )
        )
    } finally {
        client.close()
    }
}

class CollaborationConversationTransportTest {
    @Test
    fun discoveryEncodesLiteralQueryAndKeysetAndBindsNativeSession() = runTest {
        withConversations(
            MockEngine { request ->
                assertEquals(
                    "/api/v1/companies/${setupOwner.companyId}/collaboration/participants",
                    request.url.encodedPath,
                )
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(
                    "Bearer fictional-old-native",
                    request.headers[HttpHeaders.Authorization],
                )
                assertEquals("%'_ & + /", request.url.parameters["query"])
                assertEquals(CONVERSATION_ID, request.url.parameters["after"])
                assertEquals("1", request.url.parameters["limit"])
                respond(
                    """{"items":[$participantJson],"nextCursor":"$PEER_ID"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        ) { repository ->
            val page =
                assertIs<AppResult.Success<CollaborationPage<CollaborationParticipant>>>(
                        repository.participants(setupOwner, "%'_ & + /", CONVERSATION_ID, 1)
                    )
                    .value
            assertEquals(
                listOf(CollaborationParticipant(PEER_ID, "Fictional coworker")),
                page.items,
            )
            assertEquals(PEER_ID, page.nextCursor)
        }
    }

    @Test
    fun directListDetailAndRoomAreIndependentReadsAndAcceptVersion12OpaqueRoomId() = runTest {
        var count = 0
        withConversations(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                val path =
                    "/api/v1/companies/${setupOwner.companyId}/collaboration/direct-conversations"
                val result =
                    when (count++) {
                        0 -> {
                            assertEquals(path, request.url.encodedPath)
                            assertNull(request.url.parameters["after"])
                            """{"items":[$conversationJson],"nextCursor":null}"""
                        }
                        1 -> {
                            assertEquals("$path/$CONVERSATION_ID", request.url.encodedPath)
                            conversationJson
                        }
                        else -> {
                            assertEquals("$path/$CONVERSATION_ID/room", request.url.encodedPath)
                            roomJson
                        }
                    }
                respond(result, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { repository ->
            val page =
                assertIs<AppResult.Success<CollaborationPage<CollaborationDirectConversation>>>(
                        repository.list(setupOwner, null, 30)
                    )
                    .value
            val detail =
                assertIs<AppResult.Success<CollaborationDirectConversation>>(
                        repository.get(setupOwner, CONVERSATION_ID)
                    )
                    .value
            assertEquals(page.items.single(), detail)
            assertEquals(PEER_ID, detail.createdBy)
            val room =
                assertIs<AppResult.Success<CollaborationDirectRoom>>(
                        repository.room(setupOwner, CONVERSATION_ID)
                    )
                    .value
            assertEquals(CollaborationDirectRoomState.READY, room.state)
            assertEquals("!opaque-v12-room", room.matrixRoomId)
            assertFalse(room.retryable)
            assertEquals(3, count)
        }
    }

    @Test
    fun openKeepsExactCommandThroughOneNativeRefreshAndDoesNotCreateARoom() = runTest {
        val bodies = mutableListOf<String>()
        withConversations(
            MockEngine { request ->
                assertTrue(request.url.encodedPath.endsWith("/direct-conversations"))
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(SETUP_OPERATION, request.headers["Idempotency-Key"])
                bodies +=
                    (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                if (bodies.size == 1)
                    respond(
                        """{"code":"authentication_required"}""",
                        HttpStatusCode.Unauthorized,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                else {
                    assertEquals(
                        "Bearer fictional-renewed-native",
                        request.headers[HttpHeaders.Authorization],
                    )
                    respond(
                        """{"id":"$CONVERSATION_ID","version":0}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        ) { repository ->
            assertEquals(
                AppResult.Success(CollaborationSetupReceipt(CONVERSATION_ID, 0)),
                repository.open(setupOwner, CollaborationOpenDirect(SETUP_OPERATION, PEER_ID)),
            )
            assertEquals(2, bodies.size)
            assertEquals(bodies.first(), bodies.last())
            assertEquals(
                buildJsonObject { put("peerAccountId", PEER_ID) },
                Json.parseToJsonElement(bodies.first()),
            )
        }
    }

    @Test
    fun roomSetupPreservesNullInitialAndObservedRetryVersions() = runTest {
        var count = 0
        withConversations(
            MockEngine { request ->
                assertTrue(request.url.encodedPath.endsWith("/$CONVERSATION_ID/room"))
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(SETUP_OPERATION, request.headers["Idempotency-Key"])
                val input =
                    Json.parseToJsonElement(
                            (request.body as OutgoingContent.ByteArrayContent)
                                .bytes()
                                .decodeToString()
                        )
                        .jsonObject
                assertEquals(
                    setOf("deviceId", "expectedDeviceVersion", "expectedProvisioningVersion"),
                    input.keys,
                )
                assertEquals(SETUP_DEVICE, input.getValue("deviceId").jsonPrimitive.content)
                assertEquals(8, input.getValue("expectedDeviceVersion").jsonPrimitive.long)
                if (count++ == 0) assertEquals(JsonNull, input["expectedProvisioningVersion"])
                else
                    assertEquals(
                        4,
                        input.getValue("expectedProvisioningVersion").jsonPrimitive.long,
                    )
                respond(
                    """{"id":"$CONVERSATION_ID","version":5}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        ) { repository ->
            for (version in listOf(null, 4L)) assertEquals(
                AppResult.Success(CollaborationSetupReceipt(CONVERSATION_ID, 5)),
                repository.requestRoom(
                    setupOwner,
                    CollaborationRequestDirectRoom(
                        SETUP_OPERATION,
                        CONVERSATION_ID,
                        SETUP_DEVICE,
                        8,
                        version,
                    ),
                ),
            )
            assertEquals(2, count)
        }
    }

    @Test
    fun serverDenialRemainsClassifiedWithoutAdapterRetry() = runTest {
        var count = 0
        withConversations(
            MockEngine {
                count++
                respond(
                    """{"code":"collaboration_peer_unavailable","detail":"private fixture"}""",
                    HttpStatusCode.Forbidden,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
            }
        ) { repository ->
            val failure =
                assertIs<AppResult.Failed>(
                        repository.open(
                            setupOwner,
                            CollaborationOpenDirect(SETUP_OPERATION, PEER_ID),
                        )
                    )
                    .failure
            assertEquals(FailureKind.AccessDenied, failure.kind)
            assertEquals("collaboration_peer_unavailable", failure.code)
            assertFalse(failure.message.contains("private fixture"))
            assertEquals(1, count)
        }
    }

    @Test
    fun cancellationIsNotConvertedIntoAControlResponse() = runTest {
        withConversations(MockEngine { throw CancellationException("Owned request cancelled") }) {
            repository ->
            assertFailsWith<CancellationException> { repository.room(setupOwner, CONVERSATION_ID) }
        }
    }
}
