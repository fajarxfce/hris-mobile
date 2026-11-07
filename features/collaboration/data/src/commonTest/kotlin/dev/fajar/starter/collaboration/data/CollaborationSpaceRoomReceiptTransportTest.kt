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

class CollaborationSpaceRoomReceiptTransportTest {
    private val f = SpaceConversationTransportFixture()
    private val original =
        CollaborationSpaceRoomOperation(
            f.operation,
            f.owner.identityBinding(),
            f.id,
            3,
            CollaborationSpaceRoomRequest(f.device, 7, CollaborationSpaceRoomAttempt(f.peer, 11)),
            CollaborationNativePlatform.ANDROID,
            28,
        )
    private val echo =
        CollaborationSpaceRoomOperationDto(
            f.operation,
            f.owner.spaceId,
            f.id,
            f.owner.parent.nativeSessionId,
            3,
            f.device,
            7,
            11,
            f.peer,
            "ANDROID",
            28,
        )
    private val observed =
        CollaborationSpaceRoomReceiptObservationDto(
            f.owner.parent.accountId,
            echo,
            CollaborationReceiptDto(f.id, 12),
        )

    @Test
    fun replacementSessionAndCurrentBuildReadExactOldProofWithoutCreatingACommand() = runTest {
        val currentOwner =
            f.owner.copy(
                parent =
                    f.owner.parent.copy(sessionId = "replacement-login", nativeSessionId = f.other)
            )
        val current = f.conversation.copy(owner = currentOwner)
        var calls = 0
        val bodies = mutableListOf<String>()
        f.withRepositories(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("${f.root}/${f.id}/room/receipts/read", request.url.encodedPath)
                assertTrue(request.url.parameters.isEmpty())
                assertNull(request.headers["Idempotency-Key"])
                assertNull(request.headers["X-Company-Id"])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertEquals("ANDROID", request.headers["X-HRIS-Client-Platform"])
                assertEquals("29", request.headers["X-HRIS-Client-Build"])
                assertEquals(
                    if (calls == 0) "Bearer fictional-native" else "Bearer rotated-native",
                    request.headers[HttpHeaders.Authorization],
                )
                val body = request.body.toByteArray().decodeToString()
                bodies += body
                assertEquals(
                    CollaborationSpaceRoomReceiptReadDto(
                        f.operation,
                        f.owner.parent.nativeSessionId,
                        3,
                        f.device,
                        7,
                        "ANDROID",
                        28,
                        11,
                        f.peer,
                    ),
                    Json.decodeFromString<CollaborationSpaceRoomReceiptReadDto>(body),
                )
                if (calls++ == 0) respond("{}", HttpStatusCode.Unauthorized, f.headers)
                else respond(Json.encodeToString(observed), headers = f.headers)
            },
            currentOwner = currentOwner,
        ) { _, repo ->
            assertEquals(
                AppResult.Success(
                    CollaborationSpaceRoomReceiptObservation(
                        original,
                        CollaborationSetupReceipt(f.id, 12),
                    )
                ),
                repo.readReceipt(current, original),
            )
        }
        assertEquals(2, calls)
        assertEquals(1, bodies.distinct().size)
    }

    @Test
    fun uncertainAbsenceKeepsTheOriginalAndALaterReadCanObserveItsReceipt() = runTest {
        val initial = original.copy(request = original.request.copy(expectedAttempt = null))
        val response =
            observed.copy(
                original =
                    echo.copy(expectedRequestRevision = null, expectedRequestAccountId = null)
            )
        var calls = 0
        f.withRepositories(
            MockEngine { request ->
                val input =
                    Json.decodeFromString<CollaborationSpaceRoomReceiptReadDto>(
                        request.body.toByteArray().decodeToString()
                    )
                assertEquals(f.operation, input.operationId)
                assertNull(input.expectedRequestRevision)
                assertNull(input.expectedRequestAccountId)
                val value = if (calls++ == 0) response.copy(receipt = null) else response
                respond(Json.encodeToString(value), headers = f.headers)
            }
        ) { _, repo ->
            val missing =
                assertIs<AppResult.Success<CollaborationSpaceRoomReceiptObservation>>(
                        repo.readReceipt(f.conversation, initial)
                    )
                    .value
            assertEquals(initial, missing.original)
            assertNull(missing.receipt)
            val later =
                assertIs<AppResult.Success<CollaborationSpaceRoomReceiptObservation>>(
                        repo.readReceipt(f.conversation, initial)
                    )
                    .value
            assertEquals(initial, later.original)
            assertEquals(CollaborationSetupReceipt(f.id, 12), later.receipt)
        }
        assertEquals(2, calls)
    }

    @Test
    fun foreignOwnershipAndInvalidOriginalProofNeverReachTransport() = runTest {
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
                original.copy(nativeSessionVersion = -1),
                original.copy(nativeSessionVersion = 1_000_000),
                original.copy(clientBuild = 0),
                original.copy(clientBuild = 1_000_000_000),
                original.copy(request = original.request.copy(deviceId = "bad")),
                original.copy(request = original.request.copy(expectedDeviceVersion = 1_000_000)),
                original.copy(
                    request =
                        original.request.copy(
                            expectedAttempt = CollaborationSpaceRoomAttempt(f.other, 1)
                        )
                ),
                original.copy(
                    request =
                        original.request.copy(
                            expectedAttempt = CollaborationSpaceRoomAttempt(f.peer, -1)
                        )
                ),
            )
        f.withRepositories(MockEngine { error("Invalid original reached HTTP") }) { _, repo ->
            invalid.forEach { assertIs<AppResult.Failed>(repo.readReceipt(f.conversation, it)) }
        }
    }

    @Test
    fun everyOriginalFingerprintFieldMustMatchEvenWhenTheReceiptLooksValid() = runTest {
        val invalid =
            listOf(
                echo.copy(operationId = f.other),
                echo.copy(spaceId = f.other),
                echo.copy(conversationId = f.other),
                echo.copy(nativeSessionId = f.other),
                echo.copy(nativeSessionVersion = 4),
                echo.copy(deviceId = f.other),
                echo.copy(expectedDeviceVersion = 8),
                echo.copy(expectedRequestRevision = 12),
                echo.copy(expectedRequestAccountId = f.owner.parent.accountId),
                echo.copy(expectedRequestRevision = null),
                echo.copy(expectedRequestAccountId = null),
                echo.copy(platform = "IOS"),
                echo.copy(clientBuild = 29),
            )
        for (value in invalid) {
            f.withRepositories(
                MockEngine {
                    respond(
                        Json.encodeToString(observed.copy(original = value)),
                        headers = f.headers,
                    )
                }
            ) { _, repo ->
                assertIs<AppResult.Failed>(repo.readReceipt(f.conversation, original))
            }
        }
    }

    @Test
    fun anotherAccountOrMalformedReceiptCannotConfirmTheOriginal() = runTest {
        val invalid =
            listOf(
                observed.copy(accountId = f.peer),
                observed.copy(receipt = CollaborationReceiptDto(f.other, 12)),
                observed.copy(receipt = CollaborationReceiptDto(f.id, -1)),
                observed.copy(receipt = CollaborationReceiptDto(f.id, 1_000_000)),
            )
        for (value in invalid) {
            f.withRepositories(
                MockEngine { respond(Json.encodeToString(value), headers = f.headers) }
            ) { _, repo ->
                assertIs<AppResult.Failed>(repo.readReceipt(f.conversation, original))
            }
        }
    }

    @Test
    fun currentDenialAndConflictRemainFailuresWithoutAutomaticBusinessRetry() = runTest {
        for ((status, kind) in
            listOf(
                HttpStatusCode.Forbidden to FailureKind.AccessDenied,
                HttpStatusCode.Conflict to FailureKind.Conflict,
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
                    assertIs<AppResult.Failed>(repo.readReceipt(f.conversation, original))
                        .failure
                        .kind,
                )
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun aSecondUnauthorizedResponseStopsCredentialReplay() = runTest {
        var calls = 0
        f.withRepositories(
            MockEngine {
                calls++
                respond("{}", HttpStatusCode.Unauthorized, f.headers)
            }
        ) { _, repo ->
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(repo.readReceipt(f.conversation, original)).failure.kind,
            )
        }
        assertEquals(2, calls)
    }

    @Test
    fun responseIsBoundedBeforeMapping() = runTest {
        f.withRepositories(
            MockEngine {
                respond(" ".repeat(4097) + Json.encodeToString(observed), headers = f.headers)
            }
        ) { _, repo ->
            assertIs<AppResult.Failed>(repo.readReceipt(f.conversation, original))
        }
    }

    @Test
    fun cancellationCannotPublishALateReceipt() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        f.withRepositories(
            MockEngine {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                respond(Json.encodeToString(observed), headers = f.headers)
            }
        ) { _, repo ->
            val work = launch {
                repo.readReceipt(f.conversation, original)
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
