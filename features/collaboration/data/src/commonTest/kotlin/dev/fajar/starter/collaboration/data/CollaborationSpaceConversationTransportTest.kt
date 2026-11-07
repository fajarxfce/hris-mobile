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

class CollaborationSpaceConversationTransportTest {
    private val f = SpaceConversationTransportFixture()

    @Test
    fun nativeReadsKeepTheGroupPairAndFinalCursorWithoutCompanyAuthority() = runTest {
        var calls = 0
        f.withRepositories(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("Bearer fictional-native", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertNull(request.headers["X-Company-Id"])
                when (calls++) {
                    0 -> {
                        assertEquals(f.root, request.url.encodedPath)
                        assertEquals(f.peer, request.url.parameters["after"])
                        assertEquals("1", request.url.parameters["limit"])
                        respond(
                            Json.encodeToString(
                                CollaborationSpaceConversationPageDto(listOf(f.dto), f.id)
                            ),
                            headers = f.headers,
                        )
                    }
                    1 -> {
                        assertEquals("${f.root}/${f.id}", request.url.encodedPath)
                        assertTrue(request.url.parameters.isEmpty())
                        respond(Json.encodeToString(f.dto), headers = f.headers)
                    }
                    else -> respond("{\"items\":[],\"nextCursor\":null}", headers = f.headers)
                }
            }
        ) { repo, _ ->
            val page =
                assertIs<AppResult.Success<CollaborationSpaceConversationPage>>(
                        repo.list(f.owner, f.peer, 1)
                    )
                    .value
            assertEquals(f.id, page.next)
            assertEquals(f.conversation, page.items.single())
            assertEquals(AppResult.Success(f.conversation), repo.get(f.owner, f.id))
            val end =
                assertIs<AppResult.Success<CollaborationSpaceConversationPage>>(
                        repo.list(f.owner, page.next, 1)
                    )
                    .value
            assertTrue(end.items.isEmpty())
            assertNull(end.next)
            assertEquals(123456000, page.items.single().createdAt.nanosecondsOfSecond)
            assertEquals(3, calls)
        }
    }

    @Test
    fun oppositeParticipantsRetainTheSameImmutablePair() = runTest {
        val response = f.dto.copy(createdBy = f.owner.parent.accountId)
        f.withRepositories(
            MockEngine { respond(Json.encodeToString(response), headers = f.headers) }
        ) { repo, _ ->
            val first =
                assertIs<AppResult.Success<CollaborationSpaceConversation>>(repo.get(f.owner, f.id))
                    .value
            val peerOwner = f.owner.copy(parent = f.owner.parent.copy(accountId = f.peer))
            val second =
                assertIs<AppResult.Success<CollaborationSpaceConversation>>(
                        repo.get(peerOwner, f.id)
                    )
                    .value
            assertEquals(first.copy(owner = peerOwner), second)
        }
    }

    @Test
    fun invalidCoordinatesAndSelfOpenCannotReachHttp() = runTest {
        f.withRepositories(MockEngine { error("Invalid input reached HTTP") }) { repo, _ ->
            for (owner in
                listOf(
                    f.owner.copy(spaceId = "../company"),
                    f.owner.copy(parent = f.owner.parent.copy(audience = "https://foreign.test/")),
                    f.owner.copy(parent = f.owner.parent.copy(sessionId = "line\n")),
                    f.owner.copy(
                        parent =
                            f.owner.parent.copy(
                                nativeSessionId = "00000000-0000-0000-0000-000000000000"
                            )
                    ),
                )) {
                assertIs<AppResult.Failed>(repo.list(owner, null, 1))
                assertIs<AppResult.Failed>(repo.get(owner, f.id))
                assertIs<AppResult.Failed>(repo.open(owner, f.operation, f.peer))
            }
            assertIs<AppResult.Failed>(repo.list(f.owner, null, 0))
            assertIs<AppResult.Failed>(repo.list(f.owner, null, 51))
            assertIs<AppResult.Failed>(repo.list(f.owner, "../", 1))
            assertIs<AppResult.Failed>(repo.get(f.owner, f.id.uppercase()))
            assertIs<AppResult.Failed>(repo.open(f.owner, f.operation, f.owner.parent.accountId))
            assertIs<AppResult.Failed>(
                repo.open(f.owner, "00000000-0000-0000-0000-000000000000", f.peer)
            )
        }
    }

    @Test
    fun foreignUnownedOrMutableConversationFailsAsAWhole() = runTest {
        val invalid =
            listOf(
                f.dto.copy(id = f.other),
                f.dto.copy(spaceId = f.other),
                f.dto.copy(
                    firstAccountId = f.dto.secondAccountId,
                    secondAccountId = f.dto.firstAccountId,
                ),
                f.dto.copy(firstAccountId = f.other, secondAccountId = f.operation),
                f.dto.copy(firstAccountId = f.dto.secondAccountId),
                f.dto.copy(createdBy = f.other),
                f.dto.copy(version = 1),
                f.dto.copy(createdAt = "2026-11-07T00:00:00.123456789Z"),
                f.dto.copy(id = "00000000-0000-0000-0000-000000000000"),
            )
        for (dto in invalid) {
            f.withRepositories(
                MockEngine { respond(Json.encodeToString(dto), headers = f.headers) }
            ) { repo, _ ->
                assertIs<AppResult.Failed>(repo.get(f.owner, f.id), dto.toString())
            }
        }
    }

    @Test
    fun duplicateReversedOversizedAndStalePagesDoNotPublishPartialData() = runTest {
        val later = f.dto.copy(id = f.other)
        val invalid =
            listOf(
                CollaborationSpaceConversationPageDto(listOf(f.dto, f.dto), null),
                CollaborationSpaceConversationPageDto(listOf(later, f.dto), null),
                CollaborationSpaceConversationPageDto(
                    listOf(f.dto, later, later.copy(id = f.operation)),
                    null,
                ),
                CollaborationSpaceConversationPageDto(emptyList(), f.id),
                CollaborationSpaceConversationPageDto(listOf(f.dto), f.id),
                CollaborationSpaceConversationPageDto(listOf(f.dto, later), f.id),
                CollaborationSpaceConversationPageDto(
                    listOf(f.dto, later.copy(spaceId = f.other)),
                    null,
                ),
            )
        for (page in invalid) {
            f.withRepositories(
                MockEngine { respond(Json.encodeToString(page), headers = f.headers) }
            ) { repo, _ ->
                assertIs<AppResult.Failed>(repo.list(f.owner, null, 2))
            }
        }
        f.withRepositories(
            MockEngine {
                respond(
                    Json.encodeToString(CollaborationSpaceConversationPageDto(listOf(f.dto), null)),
                    headers = f.headers,
                )
            }
        ) { repo, _ ->
            assertIs<AppResult.Failed>(repo.list(f.owner, f.id, 1))
        }
    }

    @Test
    fun openKeepsItsOriginalBodyAndOperationThroughCredentialRotationAndExplicitReplay() = runTest {
        val bodies = mutableListOf<String>()
        var calls = 0
        f.withRepositories(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(f.root, request.url.encodedPath)
                assertEquals(f.operation, request.headers["Idempotency-Key"])
                assertNull(request.headers["X-Company-Id"])
                val body = request.body.toByteArray().decodeToString()
                assertEquals(
                    CollaborationSpaceOpenRequestDto(f.peer),
                    Json.decodeFromString<CollaborationSpaceOpenRequestDto>(body),
                )
                bodies += body
                if (calls++ == 0) respond("{}", HttpStatusCode.Unauthorized, f.headers)
                else
                    respond(
                        Json.encodeToString(CollaborationReceiptDto(f.id, 0)),
                        headers = f.headers,
                    )
            }
        ) { repo, _ ->
            repeat(2) {
                assertEquals(
                    AppResult.Success(CollaborationSetupReceipt(f.id, 0)),
                    repo.open(f.owner, f.operation, f.peer),
                )
            }
            assertEquals(3, calls)
            assertEquals(1, bodies.distinct().size)
        }
    }

    @Test
    fun uncertainOpenDoesNotAutoRetryOrAcceptAnotherVersion() = runTest {
        var calls = 0
        f.withRepositories(
            MockEngine {
                calls++
                respond("{}", HttpStatusCode.ServiceUnavailable, f.headers)
            }
        ) { repo, _ ->
            assertIs<AppResult.Failed>(repo.open(f.owner, f.operation, f.peer))
            assertEquals(1, calls)
        }
        for (receipt in
            listOf(
                CollaborationReceiptDto(f.id, 1),
                CollaborationReceiptDto("00000000-0000-0000-0000-000000000000", 0),
            )) {
            f.withRepositories(
                MockEngine { respond(Json.encodeToString(receipt), headers = f.headers) }
            ) { repo, _ ->
                assertIs<AppResult.Failed>(repo.open(f.owner, f.operation, f.peer))
            }
        }
    }

    @Test
    fun cancellationStopsAPendingOpenAndSuppressesItsLateResponse() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        f.withRepositories(
            MockEngine {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                respond(Json.encodeToString(CollaborationReceiptDto(f.id, 0)), headers = f.headers)
            }
        ) { repo, _ ->
            val work = launch {
                repo.open(f.owner, f.operation, f.peer)
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
