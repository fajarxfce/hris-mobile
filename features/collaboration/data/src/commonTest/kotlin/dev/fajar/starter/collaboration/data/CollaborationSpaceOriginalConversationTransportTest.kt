package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceOriginalConversationApi
import dev.fajar.starter.collaboration.data.datasources.ApiCollaborationSpaceOriginalConversationRemoteSource
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationSpaceOriginalConversationRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CollaborationSpaceOriginalConversationTransportTest {
    private val owner =
        CollaborationSpaceOwner(
            CollaborationNativeParent(
                setupOwner.sessionId,
                setupOwner.audience,
                setupOwner.accountId,
                setupOwner.nativeSessionId,
            ),
            "70000000-0000-4000-8000-000000000001",
        )
    private val peer = "80000000-0000-4000-8000-000000000001"
    private val company = "90000000-0000-4000-8000-000000000001"
    private val id = "a0000000-0000-4000-8000-000000000001"
    private val position = CollaborationOriginalConversationPosition(company, id)
    private val pair = listOf(owner.parent.accountId, peer).sorted()
    private val conversation =
        CollaborationOriginalConversationDto(
            id,
            company,
            pair[0],
            pair[1],
            owner.parent.accountId,
            "2026-11-07T00:00:00.123456Z",
            0,
        )
    private val room =
        CollaborationOriginalRoomDto(
            "matrix.example.test:8448",
            "!original:elsewhere.test",
            "2026-11-07T00:00:01.123456Z",
            peer,
        )
    private val item = CollaborationSpaceOriginalConversationDto(owner.spaceId, conversation, room)
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun nativeListAndExactLookupKeepCompositeCoordinatesAndDistinctCreators() = runTest {
        var calls = 0
        val prior = position.copy(companyId = "10000000-0000-4000-8000-000000000001")
        withRepository(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("Bearer fictional-native", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertNull(request.headers["X-Company-Id"])
                val root = "/api/v1/collaboration/spaces/${owner.spaceId}/legacy-conversations"
                if (calls++ == 0) {
                    assertEquals(root, request.url.encodedPath)
                    assertEquals(prior.companyId, request.url.parameters["afterCompanyId"])
                    assertEquals(
                        prior.conversationId,
                        request.url.parameters["afterConversationId"],
                    )
                    assertEquals("1", request.url.parameters["limit"])
                    respond(
                        Json.encodeToString(
                            CollaborationSpaceOriginalConversationPageDto(
                                listOf(item),
                                CollaborationOriginalConversationPositionDto(company, id),
                            )
                        ),
                        headers = headers,
                    )
                } else {
                    assertEquals("$root/$company/$id", request.url.encodedPath)
                    assertTrue(request.url.parameters.isEmpty())
                    respond(Json.encodeToString(item), headers = headers)
                }
            }
        ) { repo ->
            val page =
                assertIs<AppResult.Success<CollaborationSpaceOriginalConversationPage>>(
                        repo.list(owner, prior, 1)
                    )
                    .value
            assertEquals(position, page.next)
            val value =
                assertIs<AppResult.Success<CollaborationSpaceOriginalConversation>>(
                        repo.get(owner, position)
                    )
                    .value
            assertEquals(page.items.single(), value)
            assertEquals(owner.parent.accountId, value.conversation.createdBy)
            assertEquals(peer, value.room?.createdBy)
            assertEquals(Instant.parse(conversation.createdAt), value.conversation.createdAt)
            assertEquals(123456000, value.conversation.createdAt.nanosecondsOfSecond)
            assertEquals(position, value.conversation.position)
            assertEquals(2, calls)
        }
    }

    @Test
    fun sameConversationIdInDifferentCompaniesRemainsDistinctAndFinalCursorStaysAbsent() = runTest {
        val other =
            item.copy(
                conversation =
                    conversation.copy(companyId = "90000000-0000-4000-8000-000000000002"),
                room = null,
            )
        withRepository(
            MockEngine {
                respond(
                    Json.encodeToString(
                        CollaborationSpaceOriginalConversationPageDto(listOf(item, other), null)
                    ),
                    headers = headers,
                )
            }
        ) { repo ->
            val page =
                assertIs<AppResult.Success<CollaborationSpaceOriginalConversationPage>>(
                        repo.list(owner, null, 2)
                    )
                    .value
            assertEquals(2, page.items.map { it.conversation.position }.distinct().size)
            assertNull(page.items[1].room)
            assertNull(page.next)
        }
        withRepository(MockEngine { respond("{\"items\":[],\"next\":null}", headers = headers) }) {
            repo ->
            val page =
                assertIs<AppResult.Success<CollaborationSpaceOriginalConversationPage>>(
                        repo.list(owner, position, 1)
                    )
                    .value
            assertTrue(page.items.isEmpty())
            assertNull(page.next)
        }
    }

    @Test
    fun invalidRequestCoordinatesAndForeignAudienceNeverReachTransport() = runTest {
        withRepository(MockEngine { error("Invalid original coordinate reached transport") }) { repo
            ->
            for (invalid in
                listOf(
                    owner.copy(spaceId = "../company"),
                    owner.copy(spaceId = "00000000-0000-0000-0000-000000000000"),
                    owner.copy(
                        parent =
                            owner.parent.copy(
                                nativeSessionId = "00000000-0000-0000-0000-000000000000"
                            )
                    ),
                    owner.copy(parent = owner.parent.copy(audience = "https://foreign.test/")),
                    owner.copy(parent = owner.parent.copy(sessionId = "line\n")),
                )) {
                assertIs<AppResult.Failed>(repo.list(invalid, null, 1))
                assertIs<AppResult.Failed>(repo.get(invalid, position))
            }
            assertIs<AppResult.Failed>(repo.list(owner, null, 51))
            assertIs<AppResult.Failed>(
                repo.get(
                    owner,
                    position.copy(conversationId = "00000000-0000-0000-0000-000000000000"),
                )
            )
            assertIs<AppResult.Failed>(
                repo.list(
                    owner,
                    position.copy(companyId = "00000000-0000-0000-0000-000000000000"),
                    1,
                )
            )
            assertIs<AppResult.Failed>(repo.list(owner, position.copy(companyId = "bad"), 1))
            assertIs<AppResult.Failed>(repo.get(owner, position.copy(conversationId = "../room")))
        }
    }

    @Test
    fun mismatchedScopePairCoordinatesVersionsTimesAndRoomProvenanceFailWholeResponse() = runTest {
        val invalid =
            listOf(
                item.copy(spaceId = peer),
                item.copy(conversation = conversation.copy(companyId = peer)),
                item.copy(conversation = conversation.copy(id = peer)),
                item.copy(
                    conversation = conversation.copy(firstAccountId = peer, secondAccountId = peer)
                ),
                item.copy(
                    conversation =
                        conversation.copy(firstAccountId = pair[1], secondAccountId = pair[0])
                ),
                item.copy(
                    conversation = conversation.copy(firstAccountId = company, secondAccountId = id)
                ),
                item.copy(conversation = conversation.copy(createdBy = id)),
                item.copy(conversation = conversation.copy(version = 1)),
                item.copy(
                    conversation = conversation.copy(createdAt = "2026-11-07T00:00:00.123456001Z")
                ),
                item.copy(conversation = conversation.copy(createdAt = "+10000-01-01T00:00:00Z")),
                item.copy(
                    room = room.copy(boundAt = conversation.createdAt.replace("2026", "2025"))
                ),
                item.copy(room = room.copy(createdBy = id)),
                item.copy(room = room.copy(serverName = "https://foreign.test")),
                item.copy(room = room.copy(serverName = "matrix.example.test:0")),
                item.copy(room = room.copy(roomId = "!bad\nroom")),
            )
        for (value in invalid) withRepository(
            MockEngine { respond(Json.encodeToString(value), headers = headers) }
        ) { repo ->
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repo.get(owner, position)).failure.kind,
            )
        }
    }

    @Test
    fun cursorLoopsDuplicatesReversedAndOversizedPagesAreRejected() = runTest {
        val larger =
            item.copy(
                conversation = conversation.copy(companyId = "90000000-0000-4000-8000-000000000002")
            )
        val cursor = CollaborationOriginalConversationPositionDto(company, id)
        val invalid =
            listOf(
                CollaborationSpaceOriginalConversationPageDto(listOf(item, item), null),
                CollaborationSpaceOriginalConversationPageDto(listOf(larger, item), null),
                CollaborationSpaceOriginalConversationPageDto(listOf(item, larger, item), null),
                CollaborationSpaceOriginalConversationPageDto(listOf(item), cursor),
                CollaborationSpaceOriginalConversationPageDto(emptyList(), cursor),
                CollaborationSpaceOriginalConversationPageDto(listOf(item, larger), cursor),
            )
        for (value in invalid) withRepository(
            MockEngine { respond(Json.encodeToString(value), headers = headers) }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.list(owner, null, 2))
        }
        withRepository(
            MockEngine {
                respond(
                    Json.encodeToString(
                        CollaborationSpaceOriginalConversationPageDto(listOf(item), null)
                    ),
                    headers = headers,
                )
            }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.list(owner, position, 1))
        }
    }

    @Test
    fun unknownOrPartiallyBoundRoomsAndOversizedBodiesFailSafely() = runTest {
        val raw = Json.encodeToString(item)
        for (body in
            listOf(
                raw.replace("\"createdBy\":\"$peer\"", "\"createdBy\":null"),
                raw.replace("\"roomId\":\"${room.roomId}\"", "\"roomId\":null"),
                " ".repeat(4097),
            )) {
            withRepository(MockEngine { respond(body, headers = headers) }) { repo ->
                assertIs<AppResult.Failed>(repo.get(owner, position))
            }
        }
        withRepository(MockEngine { respond(" ".repeat(131073), headers = headers) }) { repo ->
            assertIs<AppResult.Failed>(repo.list(owner, null, 50))
        }
    }

    @Test
    fun remoteDenialsRetainTheirClassificationAndCancellationReleasesTheHandler() = runTest {
        withRepository(
            MockEngine {
                respond(
                    "{\"message\":\"Denied\",\"code\":\"collaboration_access_denied\"}",
                    HttpStatusCode.Forbidden,
                    headers,
                )
            }
        ) { repo ->
            val result = assertIs<AppResult.Failed>(repo.get(owner, position))
            assertEquals(FailureKind.AccessDenied, result.failure.kind)
        }
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        withRepository(
            MockEngine {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        ) { repo ->
            val work = async { repo.list(owner, null, 1) }
            entered.await()
            work.cancelAndJoin()
            stopped.await()
            assertTrue(work.isCancelled)
        }
    }

    private suspend fun withRepository(
        engine: MockEngine,
        action: suspend (RemoteCollaborationSpaceOriginalConversationRepository) -> Unit,
    ) {
        val client =
            createHttpClient(engine, HttpClientSettings(SETUP_BASE)) {
                install(SessionAuthentication) {
                    origin = Url(SETUP_BASE)
                    acquire = { session, rejected ->
                        assertEquals(owner.parent.sessionId, session)
                        assertNull(rejected)
                        AppResult.Success("fictional-native")
                    }
                }
            }
        try {
            action(
                RemoteCollaborationSpaceOriginalConversationRepository(
                    ApiCollaborationSpaceOriginalConversationRemoteSource(
                        CollaborationSpaceOriginalConversationApi(client)
                    ),
                    IdentityServer(SETUP_BASE, "Fictional native"),
                )
            )
        } finally {
            client.close()
        }
    }
}
