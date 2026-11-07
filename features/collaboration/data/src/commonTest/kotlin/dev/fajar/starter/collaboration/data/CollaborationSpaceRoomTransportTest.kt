package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CollaborationSpaceRoomTransportTest {
    private val f = SpaceConversationTransportFixture()

    @Test
    fun absentAndQueuedRoomsRemainExplicitWhilePublishedLegacyKeepsItsOriginalRoom() = runTest {
        val replies =
            listOf(
                f.view.copy(provisioning = null, room = null),
                f.view.copy(provisioning = f.queued, room = null),
                f.view,
            )
        var calls = 0
        f.withRepositories(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("${f.root}/${f.id}/room", request.url.encodedPath)
                assertEquals("Bearer fictional-native", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers["X-Company-Id"])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertTrue(request.url.parameters.isEmpty())
                respond(Json.encodeToString(replies[calls++]), headers = f.headers)
            }
        ) { _, repo ->
            val absent =
                assertIs<AppResult.Success<CollaborationSpaceRoomView>>(repo.get(f.conversation))
                    .value
            assertNull(absent.provisioning)
            assertNull(absent.room)
            val queued =
                assertIs<AppResult.Success<CollaborationSpaceRoomView>>(repo.get(f.conversation))
                    .value
            assertEquals(CollaborationProvisioningState.QUEUED, queued.provisioning?.state)
            assertNull(queued.room)
            val ready =
                assertIs<AppResult.Success<CollaborationSpaceRoomView>>(repo.get(f.conversation))
                    .value
            assertEquals(f.conversation, ready.conversation)
            assertEquals(CollaborationSpaceRoomAttempt(f.peer, 0), ready.provisioning?.attempt)
            assertEquals(f.published.roomId, ready.room?.roomId)
            assertEquals(CollaborationSpaceRoomOrigin.LEGACY, ready.room?.origin)
            assertEquals(123456000, ready.room?.publishedAt?.nanosecondsOfSecond)
        }
    }

    @Test
    fun peerRetryRetainsAccountAndRevisionWithoutOrderingAnotherParticipantsCounter() = runTest {
        val original =
            CollaborationSpaceRoomRequest(
                f.device,
                3,
                CollaborationSpaceRoomAttempt(f.peer, 999999),
            )
        val bodies = mutableListOf<String>()
        var calls = 0
        f.withRepositories(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("${f.root}/${f.id}/room/requests", request.url.encodedPath)
                assertEquals(f.operation, request.headers["Idempotency-Key"])
                assertNull(request.headers["X-Company-Id"])
                val body = request.body.toByteArray().decodeToString()
                bodies += body
                assertEquals(
                    CollaborationSpaceRoomRequestDto(f.device, 3, 999999, f.peer),
                    Json.decodeFromString<CollaborationSpaceRoomRequestDto>(body),
                )
                if (calls++ == 0) respond("{}", HttpStatusCode.Unauthorized, f.headers)
                else
                    respond(
                        Json.encodeToString(CollaborationReceiptDto(f.id, 0)),
                        headers = f.headers,
                    )
            }
        ) { _, repo ->
            repeat(2) {
                assertEquals(
                    AppResult.Success(CollaborationSetupReceipt(f.id, 0)),
                    repo.request(f.conversation, f.operation, original),
                )
            }
            assertEquals(3, calls)
            assertEquals(1, bodies.distinct().size)
        }
    }

    @Test
    fun initialRoomRequestCarriesNeitherHalfOfARetryTuple() = runTest {
        f.withRepositories(
            MockEngine { request ->
                val body =
                    Json.decodeFromString<CollaborationSpaceRoomRequestDto>(
                        request.body.toByteArray().decodeToString()
                    )
                assertEquals(CollaborationSpaceRoomRequestDto(f.device, 0, null, null), body)
                respond(Json.encodeToString(CollaborationReceiptDto(f.id, 7)), headers = f.headers)
            }
        ) { _, repo ->
            assertEquals(
                AppResult.Success(CollaborationSetupReceipt(f.id, 7)),
                repo.request(
                    f.conversation,
                    f.operation,
                    CollaborationSpaceRoomRequest(f.device, 0),
                ),
            )
        }
    }

    @Test
    fun aServerPayloadConflictCannotBecomeAReceiptOrANewOperation() = runTest {
        var calls = 0
        val bodies = mutableListOf<String>()
        f.withRepositories(
            MockEngine { request ->
                assertEquals(f.operation, request.headers["Idempotency-Key"])
                bodies += request.body.toByteArray().decodeToString()
                if (calls++ == 0) respond("{}", HttpStatusCode.Unauthorized, f.headers)
                else
                    respond(
                        "{\"code\":\"idempotency_conflict\",\"message\":\"Conflict\"}",
                        HttpStatusCode.Conflict,
                        f.headers,
                    )
            }
        ) { _, repo ->
            val result =
                assertIs<AppResult.Failed>(
                    repo.request(
                        f.conversation,
                        f.operation,
                        CollaborationSpaceRoomRequest(f.device, 0),
                    )
                )
            assertEquals(FailureKind.Conflict, result.failure.kind)
            assertEquals(2, calls)
            assertEquals(1, bodies.distinct().size)
        }
    }

    @Test
    fun foreignRequestProofAndInvalidConversationAreRejectedBeforeHttp() = runTest {
        f.withRepositories(MockEngine { error("Invalid room proof reached HTTP") }) { _, repo ->
            for (request in
                listOf(
                    CollaborationSpaceRoomRequest("../device", 0),
                    CollaborationSpaceRoomRequest(f.device, -1),
                    CollaborationSpaceRoomRequest(f.device, 1_000_000),
                    CollaborationSpaceRoomRequest(
                        f.device,
                        0,
                        CollaborationSpaceRoomAttempt(f.other, 0),
                    ),
                    CollaborationSpaceRoomRequest(
                        f.device,
                        0,
                        CollaborationSpaceRoomAttempt(f.peer, 1_000_000),
                    ),
                )) assertIs<AppResult.Failed>(repo.request(f.conversation, f.operation, request))
            val input = CollaborationSpaceRoomRequest(f.device, 0)
            assertIs<AppResult.Failed>(repo.request(f.conversation, "../operation", input))
            for (conversation in
                listOf(
                    f.conversation.copy(id = "../company"),
                    f.conversation.copy(version = 1),
                    f.conversation.copy(
                        owner =
                            f.owner.copy(
                                parent = f.owner.parent.copy(audience = "https://foreign.test/")
                            )
                    ),
                    f.conversation.copy(
                        owner = f.owner.copy(parent = f.owner.parent.copy(sessionId = "line\n"))
                    ),
                )) {
                assertIs<AppResult.Failed>(repo.get(conversation))
                assertIs<AppResult.Failed>(repo.request(conversation, f.operation, input))
            }
        }
    }

    @Test
    fun swappedScopePublisherOrSuccessBindingNeverPublishesARoom() = runTest {
        val invalid =
            listOf(
                f.view.copy(spaceId = f.other),
                f.view.copy(conversationId = f.other),
                f.view.copy(provisioning = f.success.copy(spaceId = f.other)),
                f.view.copy(provisioning = f.success.copy(conversationId = f.other)),
                f.view.copy(provisioning = f.success.copy(accountId = f.other)),
                f.view.copy(provisioning = null),
                f.view.copy(provisioning = f.queued),
                f.view.copy(room = null),
                f.view.copy(room = f.published.copy(origin = "REPLACEMENT")),
                f.view.copy(room = f.published.copy(serverName = "https://matrix.example.test")),
                f.view.copy(room = f.published.copy(roomId = "!room\n")),
                f.view.copy(room = f.published.copy(publishedAt = "2026-11-06T00:00:00Z")),
            )
        for (view in invalid) {
            f.withRepositories(
                MockEngine { respond(Json.encodeToString(view), headers = f.headers) }
            ) { _, repo ->
                assertIs<AppResult.Failed>(repo.get(f.conversation))
            }
        }
    }

    @Test
    fun invalidTerminalOutcomesAndUnboundedTimingFailBeforePublication() = runTest {
        val invalid =
            listOf(
                f.success.copy(lastOutcome = null),
                f.success.copy(revision = 1),
                f.success.copy(attempts = 0),
                f.success.copy(attempts = 9),
                f.success.copy(state = "UNKNOWN"),
                f.success.copy(failure = "ROOM_MISMATCH"),
                f.success.copy(completedAt = null),
                f.success.copy(lastOutcome = f.success.lastOutcome!!.copy(at = f.requested)),
                f.success.copy(expiresAt = f.requested),
                f.success.copy(expiresAt = "2026-11-07T00:05:01.123457Z"),
                f.success.copy(availableAt = f.created),
                f.success.copy(requestedAt = "2026-11-07T00:00:01.123456789Z"),
            )
        for (status in invalid) {
            f.withRepositories(
                MockEngine {
                    respond(
                        Json.encodeToString(f.view.copy(provisioning = status)),
                        headers = f.headers,
                    )
                }
            ) { _, repo ->
                assertIs<AppResult.Failed>(repo.get(f.conversation))
            }
        }
    }

    @Test
    fun failedAndRetriedStatusRetainsTheSameParticipantsPriorOutcome() = runTest {
        val failedOutcome =
            CollaborationSpaceRoomOutcomeDto(0, "FAILED", f.completed, "ROOM_MISMATCH")
        val failed =
            f.queued.copy(
                state = "FAILED",
                attempts = 1,
                completedAt = f.completed,
                failure = "ROOM_MISMATCH",
                lastOutcome = failedOutcome,
            )
        val retried =
            f.queued.copy(
                revision = 1,
                requestedAt = f.completed,
                availableAt = f.completed,
                lastOutcome = failedOutcome,
            )
        for (status in listOf(failed, retried)) {
            f.withRepositories(
                MockEngine {
                    respond(
                        Json.encodeToString(f.view.copy(provisioning = status, room = null)),
                        headers = f.headers,
                    )
                }
            ) { _, repo ->
                val value =
                    assertIs<AppResult.Success<CollaborationSpaceRoomView>>(
                            repo.get(f.conversation)
                        )
                        .value
                assertEquals(
                    CollaborationSpaceRoomAttempt(f.peer, status.revision),
                    value.provisioning?.attempt,
                )
                assertEquals(
                    CollaborationSpaceRoomFailure.ROOM_MISMATCH,
                    value.provisioning?.lastOutcome?.failure,
                )
                assertNull(value.room)
            }
        }
        for (status in
            listOf(
                retried.copy(lastOutcome = failedOutcome.copy(revision = 1)),
                retried.copy(completedAt = f.completed),
                retried.copy(attempts = 8),
                failed.copy(failure = null),
            )) {
            f.withRepositories(
                MockEngine {
                    respond(
                        Json.encodeToString(f.view.copy(provisioning = status, room = null)),
                        headers = f.headers,
                    )
                }
            ) { _, repo ->
                assertIs<AppResult.Failed>(repo.get(f.conversation))
            }
        }
    }

    @Test
    fun roomReceiptMustKeepItsConversationAndBoundedRevision() = runTest {
        for (receipt in
            listOf(
                CollaborationReceiptDto(f.other, 0),
                CollaborationReceiptDto(f.id, -1),
                CollaborationReceiptDto(f.id, 1_000_000),
            )) {
            f.withRepositories(
                MockEngine { respond(Json.encodeToString(receipt), headers = f.headers) }
            ) { _, repo ->
                assertIs<AppResult.Failed>(
                    repo.request(
                        f.conversation,
                        f.operation,
                        CollaborationSpaceRoomRequest(f.device, 0),
                    )
                )
            }
        }
    }

    @Test
    fun denialClassificationAndResponseSizeBoundsApplyToAllFiveOperations() = runTest {
        f.withRepositories(
            MockEngine {
                respond(
                    "{\"code\":\"collaboration_access_denied\",\"message\":\"Denied\"}",
                    HttpStatusCode.Forbidden,
                    f.headers,
                )
            }
        ) { conversations, rooms ->
            val results =
                listOf(
                    conversations.list(f.owner, null, 1),
                    conversations.get(f.owner, f.id),
                    conversations.open(f.owner, f.operation, f.peer),
                    rooms.get(f.conversation),
                    rooms.request(
                        f.conversation,
                        f.operation,
                        CollaborationSpaceRoomRequest(f.device, 0),
                    ),
                )
            results.forEach {
                assertEquals(FailureKind.AccessDenied, assertIs<AppResult.Failed>(it).failure.kind)
            }
        }
        f.withRepositories(
            MockEngine { request ->
                val validBody =
                    when {
                        request.method == HttpMethod.Post ->
                            Json.encodeToString(CollaborationReceiptDto(f.id, 0))
                        request.url.encodedPath.endsWith("/room") -> Json.encodeToString(f.view)
                        request.url.encodedPath.endsWith("/${f.id}") -> Json.encodeToString(f.dto)
                        else ->
                            Json.encodeToString(
                                CollaborationSpaceConversationPageDto(listOf(f.dto), null)
                            )
                    }
                respond(" ".repeat(65_537) + validBody, headers = f.headers)
            }
        ) { conversations, rooms ->
            assertIs<AppResult.Failed>(conversations.list(f.owner, null, 1))
            assertIs<AppResult.Failed>(conversations.get(f.owner, f.id))
            assertIs<AppResult.Failed>(conversations.open(f.owner, f.operation, f.peer))
            assertIs<AppResult.Failed>(rooms.get(f.conversation))
            assertIs<AppResult.Failed>(
                rooms.request(
                    f.conversation,
                    f.operation,
                    CollaborationSpaceRoomRequest(f.device, 0),
                )
            )
        }
    }

    @Test
    fun cancellationReleasesPendingReadsAndDoesNotPublishLateRoomMutation() = runTest {
        for (mutation in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var published = false
            f.withRepositories(
                MockEngine {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    if (mutation)
                        respond(
                            Json.encodeToString(CollaborationReceiptDto(f.id, 0)),
                            headers = f.headers,
                        )
                    else respond(Json.encodeToString(f.view), headers = f.headers)
                }
            ) { _, repo ->
                val work = launch {
                    if (mutation)
                        repo.request(
                            f.conversation,
                            f.operation,
                            CollaborationSpaceRoomRequest(f.device, 0),
                        )
                    else repo.get(f.conversation)
                    published = true
                }
                entered.await()
                work.cancel()
                release.complete(Unit)
                work.join()
                assertTrue(work.isCancelled)
                assertFalse(published)
            }
        }
    }
}
