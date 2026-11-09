package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CollaborationSpaceDirectTransportTest {
    private val f = SpaceDirectTransportFixture()

    @Test
    fun oneNativeReadPreservesExplicitReadinessOriginalCoordinatesAndExpiredShortAccess() =
        runTest {
            for (ready in listOf(false, true)) {
                var calls = 0
                f.withRepository(
                    MockEngine { request ->
                        calls++
                        assertEquals(HttpMethod.Get, request.method)
                        assertEquals(
                            "${f.pair.root}/${f.pair.id}/transport",
                            request.url.encodedPath,
                        )
                        assertEquals(
                            "Bearer fictional-native",
                            request.headers[HttpHeaders.Authorization],
                        )
                        assertEquals("ANDROID", request.headers["X-HRIS-Client-Platform"])
                        assertEquals("29", request.headers["X-HRIS-Client-Build"])
                        for (name in
                            listOf(
                                "X-Company-Id",
                                "Idempotency-Key",
                                HttpHeaders.Cookie,
                            )) assertNull(request.headers[name])
                        assertTrue(request.url.parameters.isEmpty())
                        assertTrue(request.body.toByteArray().isEmpty())
                        respond(
                            Json.encodeToString(f.body.copy(recipientReady = ready)),
                            headers = f.pair.headers,
                        )
                    }
                ) { repo ->
                    val value =
                        assertIs<AppResult.Success<CollaborationSpaceDirectTransport>>(
                                repo.read(f.pair.conversation)
                            )
                            .value
                    assertEquals(ready, value.recipientReady)
                    assertEquals(f.pair.conversation, value.conversation)
                    assertEquals(7L, value.nativeSessionVersion)
                    assertEquals(f.body.matrixDeviceId, value.matrixDeviceId)
                    assertNotEquals("HRIS_${f.pair.device.replace("-", "")}", value.matrixDeviceId)
                    assertEquals(f.pair.published.roomId, value.room.original?.roomId)
                    assertTrue(
                        assertNotNull(value.device.gateway).accessExpiresAt <
                            value.device.observedAt
                    )
                    assertEquals(1, calls)
                    assertFalse(value.toString().contains(f.user))
                }
            }
        }

    @Test
    fun aNewCryptoDeviceCanKeepTheSelectedOriginalUserAndOriginalOrNewRoom() = runTest {
        val newDevice = f.body.device.copy(origin = "SPACE")
        val groupRoom = f.body.room.copy(origin = "SPACE", original = null)
        val combinations =
            listOf(
                f.body.copy(
                    device = newDevice,
                    matrixDeviceId = "HRISG_${f.pair.device.replace("-", "")}",
                ),
                f.body.copy(
                    device = newDevice,
                    matrixDeviceId = "HRISG_${f.pair.device.replace("-", "")}",
                    room = groupRoom,
                ),
                f.body.copy(room = groupRoom),
                f.body.copy(
                    device = newDevice,
                    matrixDeviceId = "HRISG_${f.pair.device.replace("-", "")}",
                    room = groupRoom,
                    matrixUserId =
                        "@hrisg_${f.pair.owner.spaceId.replace("-", "")}_${f.account}:${groupRoom.serverName}",
                ),
            )
        for (body in combinations) f.withRepository(
            MockEngine { respond(Json.encodeToString(body), headers = f.pair.headers) }
        ) { repo ->
            val value =
                assertIs<AppResult.Success<CollaborationSpaceDirectTransport>>(
                        repo.read(f.pair.conversation)
                    )
                    .value
            assertEquals(body.matrixUserId, value.matrixUserId)
            assertEquals(body.matrixDeviceId, value.matrixDeviceId)
            assertEquals(body.room.roomId, value.room.roomId)
        }
    }

    @Test
    fun anotherOwnerOrDifferentImmutableConversationCannotBeReturnedAsTheRequestedPair() = runTest {
        val bad =
            listOf(
                f.body.copy(spaceId = f.pair.other),
                f.body.copy(accountId = f.pair.peer),
                f.body.copy(nativeSessionId = f.pair.other),
                f.body.copy(nativeSessionVersion = -1),
                f.body.copy(conversation = f.body.conversation.copy(spaceId = f.pair.other)),
                f.body.copy(conversation = f.body.conversation.copy(id = f.pair.other)),
                f.body.copy(
                    conversation =
                        f.body.conversation.copy(createdBy = f.pair.owner.parent.accountId)
                ),
                f.body.copy(
                    conversation =
                        f.body.conversation.copy(createdAt = "2026-11-07T00:00:00.123457Z")
                ),
                f.body.copy(conversation = f.body.conversation.copy(version = 1)),
            )
        for ((index, body) in bad.withIndex()) f.withRepository(
            MockEngine { respond(Json.encodeToString(body), headers = f.pair.headers) }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.read(f.pair.conversation), "Foreign observation $index")
        }
    }

    @Test
    fun inactiveExpiredOrContradictoryCryptoAndRoomFactsCannotAuthorizeDelivery() = runTest {
        val gateway = assertNotNull(f.body.device.gateway)
        val invalidDevices =
            listOf(
                f.body.device.copy(id = "00000000-0000-0000-0000-000000000000"),
                f.body.device.copy(version = 0),
                f.body.device.copy(version = 1000000),
                f.body.device.copy(state = "REVOKED"),
                f.body.device.copy(origin = "UNKNOWN"),
                f.body.device.copy(platform = "WEB"),
                f.body.device.copy(gateway = null),
                f.body.device.copy(
                    gateway = gateway.copy(id = "00000000-0000-0000-0000-000000000000")
                ),
                f.body.device.copy(gateway = gateway.copy(expiresAt = f.observed)),
                f.body.device.copy(gateway = gateway.copy(revokedAt = f.observed)),
            )
        val bad =
            invalidDevices.map { f.body.copy(device = it) } +
                listOf(
                    f.body.copy(
                        matrixUserId = f.user.replace(f.account, f.pair.peer.replace("-", ""))
                    ),
                    f.body.copy(matrixUserId = f.user.replace(f.originalCompany, "a".repeat(32))),
                    f.body.copy(
                        matrixUserId = f.user.replace(f.body.room.serverName, "foreign.test")
                    ),
                    f.body.copy(
                        matrixUserId = f.user.replace(f.originalCompany, "0".repeat(32)),
                        room = f.body.room.copy(origin = "SPACE", original = null),
                    ),
                    f.body.copy(matrixDeviceId = "HRIS_${"0".repeat(32)}"),
                    f.body.copy(matrixDeviceId = "HRIS_invalid"),
                    f.body.copy(matrixDeviceId = "HRISG_${f.pair.device.replace("-", "")}"),
                    f.body.copy(device = f.body.device.copy(origin = "SPACE")),
                    f.body.copy(room = f.body.room.copy(original = null)),
                    f.body.copy(room = f.body.room.copy(origin = "SPACE")),
                    f.body.copy(room = f.body.room.copy(roomId = "!another:other.test")),
                    f.body.copy(room = f.body.room.copy(publishedAt = "2026-11-06T00:00:00Z")),
                    f.body.copy(room = f.body.room.copy(publishedAt = "2026-11-08T00:00:00Z")),
                )
        for ((index, body) in bad.withIndex()) f.withRepository(
            MockEngine { respond(Json.encodeToString(body), headers = f.pair.headers) }
        ) { repo ->
            assertIs<AppResult.Failed>(
                repo.read(f.pair.conversation),
                "Invalid current facts $index",
            )
        }
    }

    @Test
    fun invalidLocalRequestNeverReachesHttp() = runTest {
        val c = f.pair.conversation
        val p = c.owner.parent
        val invalid =
            listOf(
                c.copy(id = "../conversation"),
                c.copy(owner = c.owner.copy(spaceId = "00000000-0000-0000-0000-000000000000")),
                c.copy(owner = c.owner.copy(parent = p.copy(audience = "https://foreign.test/"))),
                c.copy(owner = c.owner.copy(parent = p.copy(sessionId = "bad session"))),
                c.copy(owner = c.owner.copy(parent = p.copy(nativeSessionId = "bad"))),
            )
        f.withRepository(MockEngine { error("Invalid local request reached HTTP") }) { repo ->
            for (conversation in invalid) assertIs<AppResult.Failed>(repo.read(conversation))
        }
    }

    @Test
    fun missingMalformedAndOversizedBodiesCannotTurnAbsenceIntoReadiness() = runTest {
        val valid = Json.encodeToString(f.body)
        val bodies =
            listOf(
                "{}",
                "null",
                "x".repeat(8193),
                valid.replace("\"recipientReady\":false", "\"recipientReady\":null"),
                valid.replace("\"nativeSessionVersion\":7,", ""),
            )
        for ((index, body) in bodies.withIndex()) f.withRepository(
            MockEngine { respond(body, headers = f.pair.headers) }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.read(f.pair.conversation), "Malformed body $index")
        }
    }

    @Test
    fun denialsAndProviderFailuresTerminateWithAtMostOneNativeAuthenticationReplay() = runTest {
        val statuses =
            listOf(
                HttpStatusCode.Forbidden to FailureKind.AccessDenied,
                HttpStatusCode.Unauthorized to FailureKind.Unauthorized,
                HttpStatusCode.ServiceUnavailable to FailureKind.Service,
            )
        for ((status, kind) in statuses) {
            var calls = 0
            f.withRepository(
                MockEngine {
                    calls++
                    respond("{\"code\":\"collaboration_access_denied\"}", status, f.pair.headers)
                }
            ) { repo ->
                assertEquals(
                    kind,
                    assertIs<AppResult.Failed>(repo.read(f.pair.conversation)).failure.kind,
                )
                assertEquals(if (status == HttpStatusCode.Unauthorized) 2 else 1, calls)
            }
        }
    }

    @Test
    fun pendingReadCancellationStopsIoWithoutAReplacementOrRetry() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var calls = 0
        f.withRepository(
            MockEngine {
                try {
                    calls++
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        ) { repo ->
            val pending = async { repo.read(f.pair.conversation) }
            entered.await()
            pending.cancelAndJoin()
            stopped.await()
            assertTrue(pending.isCancelled)
            assertEquals(1, calls)
        }
    }
}
