package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.*
import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationSpacePhysicalRoomRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceRoomView
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CollaborationSpacePhysicalRoomTransportTest {
    private val f = SpaceConversationTransportFixture()
    private val originalPosition = requireNotNull(f.published.original)
    private val original =
        CollaborationSpaceOriginalConversationDto(
            f.owner.spaceId,
            CollaborationOriginalConversationDto(
                originalPosition.conversationId,
                originalPosition.companyId,
                f.dto.firstAccountId,
                f.dto.secondAccountId,
                f.owner.parent.accountId,
                f.created,
                0,
            ),
            CollaborationOriginalRoomDto(
                f.published.serverName,
                f.published.roomId,
                f.created,
                f.peer,
            ),
        )

    @Test
    fun publishedPhysicalReadKeepsExactCoordinatesWithoutInventingConversationMetadata() = runTest {
        var calls = 0
        withRepository(
            MockEngine { request ->
                calls++
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(
                    "/api/v1/collaboration/spaces/${f.owner.spaceId}/rooms/read",
                    request.url.encodedPath,
                )
                assertEquals("Bearer fictional-native", request.headers[HttpHeaders.Authorization])
                assertEquals("ANDROID", request.headers["X-HRIS-Client-Platform"])
                assertEquals("29", request.headers["X-HRIS-Client-Build"])
                assertTrue(request.url.parameters.isEmpty())
                assertNull(request.headers["X-Company-Id"])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertNull(request.headers["Idempotency-Key"])
                assertEquals(
                    Json.encodeToString(CollaborationSpacePhysicalRoomReadDto(f.published.roomId)),
                    request.body.toByteArray().decodeToString(),
                )
                respond(Json.encodeToString(f.view), headers = f.headers)
            }
        ) { repo ->
            val read =
                assertIs<AppResult.Success<CollaborationSpacePhysicalRoomView>>(
                        repo.published(f.owner, f.published.serverName, f.published.roomId)
                    )
                    .value
            assertEquals(f.owner, read.owner)
            assertEquals(f.id, read.conversationId)
            assertEquals(f.published.serverName, read.room.serverName)
            assertEquals(originalPosition.companyId, read.room.original!!.position.companyId)
            assertTrue(
                validCollaborationSpaceRoomView(
                    CollaborationSpaceRoomView(f.conversation, read.provisioning, read.room)
                )
            )
            assertEquals(1, calls)
        }
    }

    @Test
    fun originalPhysicalReadSendsExplicitCompanyAndKeepsDistinctCreators() = runTest {
        withRepository(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(
                    "/api/v1/collaboration/spaces/${f.owner.spaceId}/legacy-rooms/read",
                    request.url.encodedPath,
                )
                assertNull(request.headers["X-Company-Id"])
                assertNull(request.headers["Idempotency-Key"])
                assertEquals(
                    Json.encodeToString(
                        CollaborationSpaceOriginalRoomReadDto(
                            originalPosition.companyId,
                            f.published.roomId,
                        )
                    ),
                    request.body.toByteArray().decodeToString(),
                )
                respond(Json.encodeToString(original), headers = f.headers)
            }
        ) { repo ->
            val read =
                assertIs<AppResult.Success<CollaborationSpaceOriginalConversation>>(
                        repo.original(
                            f.owner,
                            originalPosition.companyId,
                            f.published.serverName,
                            f.published.roomId,
                        )
                    )
                    .value
            assertEquals(originalPosition.companyId, read.conversation.position.companyId)
            assertNotEquals(f.owner.spaceId, read.conversation.position.companyId)
            assertEquals(f.owner.parent.accountId, read.conversation.createdBy)
            val room = assertNotNull(read.room)
            assertEquals(f.peer, room.createdBy)
            assertNotEquals(read.conversation.createdBy, room.createdBy)
        }
    }

    @Test
    fun nativeCredentialRotationReplaysOnlyTheSameReadBodyOnce() = runTest {
        for (legacy in listOf(false, true)) {
            val bodies = mutableListOf<String>()
            withRepository(
                MockEngine { request ->
                    assertEquals(
                        if (bodies.isEmpty()) "Bearer fictional-native"
                        else "Bearer rotated-native",
                        request.headers[HttpHeaders.Authorization],
                    )
                    assertNull(request.headers["Idempotency-Key"])
                    bodies += request.body.toByteArray().decodeToString()
                    if (bodies.size == 1) respond("{}", HttpStatusCode.Unauthorized, f.headers)
                    else
                        respond(
                            if (legacy) Json.encodeToString(original)
                            else Json.encodeToString(f.view),
                            headers = f.headers,
                        )
                }
            ) { repo ->
                val result =
                    if (legacy)
                        repo.original(
                            f.owner,
                            originalPosition.companyId,
                            f.published.serverName,
                            f.published.roomId,
                        )
                    else repo.published(f.owner, f.published.serverName, f.published.roomId)
                assertIs<AppResult.Success<*>>(result)
                assertEquals(2, bodies.size)
                assertEquals(1, bodies.distinct().size)
            }
        }
    }

    @Test
    fun aSecondUnauthorizedResponseStopsReadReplay() = runTest {
        for (legacy in listOf(false, true)) {
            var calls = 0
            withRepository(
                MockEngine {
                    calls++
                    respond("{}", HttpStatusCode.Unauthorized, f.headers)
                }
            ) { repo ->
                val result =
                    if (legacy)
                        repo.original(
                            f.owner,
                            originalPosition.companyId,
                            f.published.serverName,
                            f.published.roomId,
                        )
                    else repo.published(f.owner, f.published.serverName, f.published.roomId)
                assertEquals(
                    FailureKind.Unauthorized,
                    assertIs<AppResult.Failed>(result).failure.kind,
                )
                assertEquals(2, calls)
            }
        }
    }

    @Test
    fun invalidQueriesFailBeforeNetworkAcquisition() = runTest {
        var calls = 0
        withRepository(
            MockEngine {
                calls++
                error("No invalid query may reach HTTP")
            }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.published(f.owner, "", f.published.roomId))
            assertIs<AppResult.Failed>(repo.published(f.owner, f.published.serverName, "bad"))
            assertIs<AppResult.Failed>(
                repo.original(f.owner, "bad", f.published.serverName, f.published.roomId)
            )
            val otherAudience =
                f.owner.copy(parent = f.owner.parent.copy(audience = "https://other.test/"))
            assertIs<AppResult.Failed>(
                repo.published(otherAudience, f.published.serverName, f.published.roomId)
            )
            assertEquals(0, calls)
        }
    }

    @Test
    fun wrongScopeOrIncompletePublishedMetadataFailsWithoutRetry() = runTest {
        val invalid =
            listOf(
                f.view.copy(spaceId = f.other),
                f.view.copy(conversationId = f.other),
                f.view.copy(provisioning = null),
                f.view.copy(provisioning = f.queued),
                f.view.copy(room = null),
                f.view.copy(room = f.published.copy(roomId = "!wrong:test")),
                f.view.copy(room = f.published.copy(serverName = "other.test")),
                f.view.copy(room = f.published.copy(original = null)),
                f.view.copy(provisioning = f.success.copy(attempts = 0)),
                f.view.copy(provisioning = f.success.copy(spaceId = f.other)),
            )
        for (body in invalid) {
            var calls = 0
            withRepository(
                MockEngine {
                    calls++
                    respond(Json.encodeToString(body), headers = f.headers)
                }
            ) { repo ->
                assertIs<AppResult.Failed>(
                    repo.published(f.owner, f.published.serverName, f.published.roomId)
                )
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun actualConversationMustStillAdmitProvisioningParticipantAfterPhysicalLookup() = runTest {
        val body = f.view.copy(provisioning = f.success.copy(accountId = f.other))
        withRepository(MockEngine { respond(Json.encodeToString(body), headers = f.headers) }) {
            repo ->
            val read =
                assertIs<AppResult.Success<CollaborationSpacePhysicalRoomView>>(
                        repo.published(f.owner, f.published.serverName, f.published.roomId)
                    )
                    .value
            assertFalse(
                validCollaborationSpaceRoomView(
                    CollaborationSpaceRoomView(f.conversation, read.provisioning, read.room)
                )
            )
        }
    }

    @Test
    fun originalReadRejectsWrongCompanyRoomAndParticipant() = runTest {
        val room = requireNotNull(original.room)
        val invalid =
            listOf(
                original.copy(spaceId = f.other),
                original.copy(room = null),
                original.copy(room = room.copy(serverName = "other.test")),
                original.copy(room = room.copy(roomId = "!wrong:test")),
                original.copy(conversation = original.conversation.copy(companyId = f.other)),
                original.copy(conversation = original.conversation.copy(firstAccountId = f.other)),
            )
        for (body in invalid) withRepository(
            MockEngine { respond(Json.encodeToString(body), headers = f.headers) }
        ) { repo ->
            assertIs<AppResult.Failed>(
                repo.original(
                    f.owner,
                    originalPosition.companyId,
                    f.published.serverName,
                    f.published.roomId,
                )
            )
        }
    }

    @Test
    fun physicalReadsRejectOversizedBodiesAtTheirOwnBounds() = runTest {
        for (legacy in listOf(false, true)) withRepository(
            MockEngine {
                val raw = if (legacy) Json.encodeToString(original) else Json.encodeToString(f.view)
                respond(
                    raw + " ".repeat((if (legacy) 4097 else 8193) - raw.length),
                    headers = f.headers,
                )
            }
        ) { repo ->
            val result =
                if (legacy)
                    repo.original(
                        f.owner,
                        originalPosition.companyId,
                        f.published.serverName,
                        f.published.roomId,
                    )
                else repo.published(f.owner, f.published.serverName, f.published.roomId)
            assertIs<AppResult.Failed>(result)
        }
    }

    @Test
    fun deniedReadsKeepTheirClassificationWithoutFallbackOrRepeatedRequests() = runTest {
        for (legacy in listOf(false, true)) {
            var calls = 0
            withRepository(
                MockEngine {
                    calls++
                    respond(
                        """{"message":"Denied","code":"collaboration_access_denied"}""",
                        HttpStatusCode.Forbidden,
                        f.headers,
                    )
                }
            ) { repo ->
                val result =
                    if (legacy)
                        repo.original(
                            f.owner,
                            originalPosition.companyId,
                            f.published.serverName,
                            f.published.roomId,
                        )
                    else repo.published(f.owner, f.published.serverName, f.published.roomId)
                assertEquals(
                    FailureKind.AccessDenied,
                    assertIs<AppResult.Failed>(result).failure.kind,
                )
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun missingPublicationPreservesItsApiCodeWithTheSharedHttpClassification() = runTest {
        var calls = 0
        withRepository(
            MockEngine {
                calls++
                respond(
                    """{"message":"Missing","code":"collaboration_conversation_not_found"}""",
                    HttpStatusCode.NotFound,
                    f.headers,
                )
            }
        ) { repo ->
            val failure =
                assertIs<AppResult.Failed>(
                        repo.published(f.owner, f.published.serverName, f.published.roomId)
                    )
                    .failure
            assertEquals(FailureKind.Unexpected, failure.kind)
            assertEquals("collaboration_conversation_not_found", failure.code)
            assertEquals(1, calls)
        }
    }

    @Test
    fun cancellationReleasesEitherPendingPhysicalRead() = runTest {
        for (legacy in listOf(false, true)) {
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
                val work = async {
                    if (legacy)
                        repo.original(
                            f.owner,
                            originalPosition.companyId,
                            f.published.serverName,
                            f.published.roomId,
                        )
                    else repo.published(f.owner, f.published.serverName, f.published.roomId)
                }
                entered.await()
                work.cancelAndJoin()
                stopped.await()
            }
        }
    }

    private suspend fun withRepository(
        engine: MockEngine,
        action: suspend (RemoteCollaborationSpacePhysicalRoomRepository) -> Unit,
    ) {
        val client =
            createHttpClient(engine, HttpClientSettings(SETUP_BASE)) {
                defaultRequest {
                    header("X-HRIS-Client-Platform", "ANDROID")
                    header("X-HRIS-Client-Build", 29)
                }
                install(SessionAuthentication) {
                    origin = Url(SETUP_BASE)
                    acquire = { session, rejected ->
                        assertEquals(f.owner.parent.sessionId, session)
                        AppResult.Success(
                            if (rejected == null) "fictional-native" else "rotated-native"
                        )
                    }
                }
            }
        try {
            action(
                RemoteCollaborationSpacePhysicalRoomRepository(
                    ApiCollaborationSpacePhysicalRoomRemoteSource(
                        CollaborationSpaceRoomApi(client),
                        CollaborationSpaceOriginalConversationApi(client),
                    ),
                    IdentityServer(SETUP_BASE, "Fictional native"),
                )
            )
        } finally {
            client.cancelAndClose()
        }
    }
}
