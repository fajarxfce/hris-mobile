package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class CollaborationSpaceRoomRequestFenceTransportTest {
    private val f = SpaceConversationTransportFixture()
    private val original =
        CollaborationSpaceRoomRequestIntent(
            f.operation,
            f.owner.identityBinding(),
            f.id,
            CollaborationSpaceRoomRequest(f.device, 7, CollaborationSpaceRoomAttempt(f.peer, 11)),
        )
    private val echo =
        CollaborationSpaceRoomRequestIntentDto(
            f.operation,
            f.owner.spaceId,
            f.id,
            f.device,
            7,
            11,
            f.peer,
        )
    private val fence = CollaborationSpaceRoomRequestFenceEvidenceDto(f.other, f.completed)
    private val resolved =
        CollaborationSpaceRoomRequestResolutionDto(
            f.owner.parent.accountId,
            echo,
            "FENCED",
            null,
            fence,
        )
    private val recorded =
        resolved.copy(state = "RECORDED", receipt = CollaborationReceiptDto(f.id, 12), fence = null)

    @Test
    fun currentParentFencesTheOriginalWithOneStableMutationKeyAcrossAuthRefresh() = runTest {
        val current =
            f.owner.copy(
                parent =
                    f.owner.parent.copy(sessionId = "replacement-login", nativeSessionId = f.other)
            )
        var calls = 0
        val bodies = mutableListOf<String>()
        f.withRepositories(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("${f.root}/${f.id}/room/requests/fence", request.url.encodedPath)
                assertTrue(request.url.parameters.isEmpty())
                assertEquals(f.operation, request.headers["Idempotency-Key"])
                assertNull(request.headers["X-Company-Id"])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertEquals("29", request.headers["X-HRIS-Client-Build"])
                assertEquals("ANDROID", request.headers["X-HRIS-Client-Platform"])
                assertEquals(
                    if (calls == 0) "Bearer fictional-native" else "Bearer rotated-native",
                    request.headers[HttpHeaders.Authorization],
                )
                val body = request.body.toByteArray().decodeToString()
                bodies += body
                assertEquals(
                    setOf(
                        "operationId",
                        "deviceId",
                        "expectedDeviceVersion",
                        "expectedRequestRevision",
                        "expectedRequestAccountId",
                    ),
                    Json.parseToJsonElement(body).jsonObject.keys,
                )
                assertEquals(
                    CollaborationSpaceRoomRequestFenceDto(f.operation, f.device, 7, 11, f.peer),
                    Json.decodeFromString<CollaborationSpaceRoomRequestFenceDto>(body),
                )
                if (calls++ == 0) respond("{}", HttpStatusCode.Unauthorized, f.headers)
                else respond(Json.encodeToString(resolved), headers = f.headers)
            },
            currentOwner = current,
        ) { _, repo ->
            assertEquals(
                AppResult.Success(
                    CollaborationSpaceRoomRequestResolution.Fenced(
                        CollaborationSpaceRoomRequestFence(
                            original,
                            f.other,
                            Instant.parse(f.completed),
                        )
                    )
                ),
                repo.fenceRequest(f.conversation.copy(owner = current), original),
            )
        }
        assertEquals(2, calls)
        assertEquals(1, bodies.distinct().size)
    }

    @Test
    fun anExistingReceiptRemainsRecordedAndInitialIntentHasNoInventedAttempt() = runTest {
        val initial = original.copy(request = original.request.copy(expectedAttempt = null))
        val response =
            recorded.copy(
                original =
                    echo.copy(expectedRequestRevision = null, expectedRequestAccountId = null)
            )
        var calls = 0
        f.withRepositories(
            MockEngine { request ->
                calls++
                val input =
                    Json.decodeFromString<CollaborationSpaceRoomRequestFenceDto>(
                        request.body.toByteArray().decodeToString()
                    )
                assertEquals(f.operation, input.operationId)
                assertNull(input.expectedRequestRevision)
                assertNull(input.expectedRequestAccountId)
                respond(Json.encodeToString(response), headers = f.headers)
            }
        ) { _, repo ->
            assertEquals(
                AppResult.Success(
                    CollaborationSpaceRoomRequestResolution.Recorded(
                        initial,
                        CollaborationSetupReceipt(f.id, 12),
                    )
                ),
                repo.fenceRequest(f.conversation, initial),
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun invalidOriginalOwnershipAndInputNeverReachHttp() = runTest {
        val invalid =
            listOf(
                original.copy(
                    binding = original.binding.copy(audience = "https://other.test/api/v1/")
                ),
                original.copy(binding = original.binding.copy(accountId = f.peer)),
                original.copy(binding = original.binding.copy(spaceId = f.other)),
                original.copy(binding = original.binding.copy(nativeSessionId = "bad")),
                original.copy(conversationId = f.other),
                original.copy(operationId = "00000000-0000-0000-0000-000000000000"),
                original.copy(request = original.request.copy(deviceId = "bad")),
                original.copy(request = original.request.copy(expectedDeviceVersion = 1_000_000)),
                original.copy(
                    request =
                        original.request.copy(
                            expectedAttempt = CollaborationSpaceRoomAttempt(f.other, 1)
                        )
                ),
            )
        f.withRepositories(MockEngine { error("Invalid fence reached HTTP") }) { _, repo ->
            invalid.forEach { assertIs<AppResult.Failed>(repo.fenceRequest(f.conversation, it)) }
        }
    }

    @Test
    fun everyEchoCoordinateAndAccountMustMatchForBothResolutionKinds() = runTest {
        val echoes =
            listOf(
                echo.copy(operationId = f.other),
                echo.copy(spaceId = f.other),
                echo.copy(conversationId = f.other),
                echo.copy(deviceId = f.other),
                echo.copy(expectedDeviceVersion = 8),
                echo.copy(expectedRequestRevision = 12),
                echo.copy(expectedRequestAccountId = f.owner.parent.accountId),
                echo.copy(expectedRequestRevision = null),
                echo.copy(expectedRequestAccountId = null),
            )
        for (response in listOf(resolved, recorded)) {
            for (value in
                echoes.map { response.copy(original = it) } + response.copy(accountId = f.peer)) {
                f.withRepositories(
                    MockEngine { respond(Json.encodeToString(value), headers = f.headers) }
                ) { _, repo ->
                    assertIs<AppResult.Failed>(repo.fenceRequest(f.conversation, original))
                }
            }
        }
    }

    @Test
    fun ambiguousMissingOrMalformedEvidenceCannotAuthorizeReplacement() = runTest {
        val invalid =
            listOf(
                resolved.copy(state = "UNKNOWN"),
                resolved.copy(state = "RECORDED"),
                resolved.copy(fence = null),
                resolved.copy(receipt = CollaborationReceiptDto(f.id, 12)),
                recorded.copy(receipt = null),
                recorded.copy(fence = fence),
                recorded.copy(receipt = CollaborationReceiptDto(f.other, 12)),
                recorded.copy(receipt = CollaborationReceiptDto(f.id, -1)),
                recorded.copy(receipt = CollaborationReceiptDto(f.id, 1_000_000)),
                resolved.copy(fence = fence.copy(id = "bad")),
                resolved.copy(fence = fence.copy(id = "00000000-0000-0000-0000-000000000000")),
                resolved.copy(fence = fence.copy(createdAt = "bad")),
                resolved.copy(fence = fence.copy(createdAt = "2026-11-07T00:00:00.123455Z")),
                resolved.copy(fence = fence.copy(createdAt = "2026-11-07T00:00:02.123456789Z")),
                resolved.copy(fence = fence.copy(createdAt = "+10000-01-01T00:00:00Z")),
            )
        for (value in invalid) {
            f.withRepositories(
                MockEngine { respond(Json.encodeToString(value), headers = f.headers) }
            ) { _, repo ->
                assertIs<AppResult.Failed>(repo.fenceRequest(f.conversation, original))
            }
        }
        val complete = Json.parseToJsonElement(Json.encodeToString(resolved)).jsonObject
        for (missing in complete.keys) {
            f.withRepositories(
                MockEngine {
                    respond(JsonObject(complete - missing).toString(), headers = f.headers)
                }
            ) { _, repo ->
                assertIs<AppResult.Failed>(repo.fenceRequest(f.conversation, original))
            }
        }
    }

    @Test
    fun currentDenialConflictAndUnavailableResponsesAreNotRetriedOrConvertedToEvidence() = runTest {
        for ((status, kind) in
            listOf(
                HttpStatusCode.Forbidden to FailureKind.AccessDenied,
                HttpStatusCode.Conflict to FailureKind.Conflict,
                HttpStatusCode.ServiceUnavailable to FailureKind.Service,
            )) {
            var calls = 0
            f.withRepositories(
                MockEngine {
                    calls++
                    respond(
                        "{\"code\":\"collaboration_access_denied\",\"message\":\"Denied\"}",
                        status,
                        f.headers,
                    )
                }
            ) { _, repo ->
                assertEquals(
                    kind,
                    assertIs<AppResult.Failed>(repo.fenceRequest(f.conversation, original))
                        .failure
                        .kind,
                )
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun oversizedResolutionIsNotAccepted() = runTest {
        f.withRepositories(
            MockEngine {
                respond(" ".repeat(4097) + Json.encodeToString(resolved), headers = f.headers)
            }
        ) { _, repo ->
            assertIs<AppResult.Failed>(repo.fenceRequest(f.conversation, original))
        }
    }

    @Test
    fun cancelledTransportDoesNotPublishALateResolution() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        f.withRepositories(
            MockEngine {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                respond(Json.encodeToString(resolved), headers = f.headers)
            }
        ) { _, repo ->
            val work = launch {
                repo.fenceRequest(f.conversation, original)
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
