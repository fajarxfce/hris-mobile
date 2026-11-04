package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceApi
import dev.fajar.starter.collaboration.data.datasources.ApiCollaborationSpaceRemoteSource
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationSpaceRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CollaborationSpaceTransportTest {
    private val parent =
        CollaborationNativeParent(
            setupOwner.sessionId,
            setupOwner.audience,
            setupOwner.accountId,
            setupOwner.nativeSessionId,
        )
    private val owner = CollaborationSpaceOwner(parent, "70000000-0000-4000-8000-000000000001")
    private val peer = "80000000-0000-4000-8000-000000000001"
    private val company = "90000000-0000-4000-8000-000000000001"
    private val observation =
        """{"spaceId":"${owner.spaceId}","accountId":"${parent.accountId}","nativeSessionId":"${parent.nativeSessionId}","nativeSessionVersion":2,"credentialVersion":3,"admittedAt":"2026-11-04T00:00:00Z","expiresAt":"2026-11-04T00:01:00Z"}"""
    private val contact =
        """{"accountId":"$peer","displayName":"Fictional peer","companies":[{"id":"$company","name":"Other fictional company"}],"hasMoreCompanies":false}"""
    private val page = """{"items":[$contact],"nextCursor":null}"""
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun currentAndContactsUseNativeGroupPathsAndLiteralSearchWithoutCompanyHeader() = runTest {
        var calls = 0
        withRepository(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("Bearer fictional-native", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertNull(request.headers["X-Company-Id"])
                if (calls++ == 0) {
                    assertEquals("/api/v1/collaboration/spaces/current", request.url.encodedPath)
                    assertTrue(request.url.parameters.isEmpty())
                    respond(observation, headers = headers)
                } else {
                    assertEquals(
                        "/api/v1/collaboration/spaces/${owner.spaceId}/contacts",
                        request.url.encodedPath,
                    )
                    assertEquals("a_% & ?", request.url.parameters["query"])
                    assertEquals("1", request.url.parameters["limit"])
                    assertEquals(owner.spaceId, request.url.parameters["after"])
                    respond(page, headers = headers)
                }
            }
        ) { repository ->
            assertEquals(
                owner,
                assertIs<AppResult.Success<CollaborationSpaceObservation>>(
                        repository.current(parent)
                    )
                    .value
                    .owner,
            )
            val result =
                assertIs<AppResult.Success<CollaborationSpaceContactPage>>(
                        repository.contacts(owner, "  a_% & ?  ", owner.spaceId, 1)
                    )
                    .value
            assertEquals(company, result.items.single().companies.single().id)
            assertNull(result.nextCursor)
            assertEquals(2, calls)
        }
    }

    @Test
    fun foreignParentsAndInvalidQueryBoundsNeverReachNetwork() = runTest {
        withRepository(MockEngine { error("Invalid input reached transport") }) { repository ->
            for (value in
                listOf(
                    parent.copy(audience = "https://foreign.example.test/"),
                    parent.copy(sessionId = "line\n"),
                    parent.copy(accountId = "bad"),
                    parent.copy(nativeSessionId = "bad"),
                )) assertIs<AppResult.Failed>(repository.current(value))
            assertIs<AppResult.Failed>(
                repository.contacts(owner.copy(spaceId = "../company"), "", null, 30)
            )
            assertIs<AppResult.Failed>(repository.contacts(owner, "", "../account", 30))
            assertIs<AppResult.Failed>(repository.contacts(owner, "x".repeat(81), null, 30))
            assertIs<AppResult.Failed>(repository.contacts(owner, "", null, 51))
        }
    }

    @Test
    fun mismatchedDiscoveryIdentityVersionsAndDurationFailInsideSafeMapping() = runTest {
        val source = Json.parseToJsonElement(observation).jsonObject
        for ((key, value) in
            listOf(
                "accountId" to JsonPrimitive(peer),
                "nativeSessionId" to JsonPrimitive(peer),
                "spaceId" to JsonPrimitive("../foreign"),
                "nativeSessionVersion" to JsonPrimitive(-1),
                "credentialVersion" to JsonPrimitive(-1),
                "admittedAt" to JsonPrimitive("invalid"),
                "expiresAt" to JsonPrimitive("2026-11-04T00:01:01Z"),
            )) withRepository(
            MockEngine {
                respond(JsonObject(source + (key to value)).toString(), headers = headers)
            }
        ) {
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(it.current(parent)).failure.kind,
            )
        }
    }

    @Test
    fun contactPagesRejectSelfDuplicatesOversizeInvalidAffiliationsAndCursorLoops() = runTest {
        val source = Json.parseToJsonElement(page).jsonObject
        val item = Json.parseToJsonElement(contact).jsonObject
        val affiliation = item.getValue("companies").jsonArray.single()
        val bodies =
            listOf(
                JsonObject(source + ("nextCursor" to JsonPrimitive(peer))),
                JsonObject(source + ("items" to JsonArray(listOf(item, item)))),
                JsonObject(source + ("items" to JsonArray(List(31) { item }))),
                JsonObject(
                    source +
                        ("items" to
                            JsonArray(
                                listOf(
                                    JsonObject(
                                        item + ("accountId" to JsonPrimitive(parent.accountId))
                                    )
                                )
                            ))
                ),
                JsonObject(
                    source +
                        ("items" to
                            JsonArray(
                                listOf(
                                    JsonObject(
                                        item + ("companies" to JsonArray(List(9) { affiliation }))
                                    )
                                )
                            ))
                ),
                JsonObject(
                    source +
                        ("items" to
                            JsonArray(
                                listOf(
                                    JsonObject(item + ("hasMoreCompanies" to JsonPrimitive(true)))
                                )
                            ))
                ),
            )
        for (body in bodies) withRepository(
            MockEngine { respond(body.toString(), headers = headers) }
        ) {
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(it.contacts(owner, "", null, 30)).failure.kind,
            )
        }
        withRepository(MockEngine { respond(page, headers = headers) }) {
            assertIs<AppResult.Failed>(it.contacts(owner, "", peer, 30))
        }
    }

    @Test
    fun serverDenialAndBoundedBodiesStayFailuresWithoutFallback() = runTest {
        withRepository(
            MockEngine {
                respond(
                    """{"code":"collaboration_access_denied","message":"Denied"}""",
                    HttpStatusCode.Forbidden,
                    headers,
                )
            }
        ) {
            val failure = assertIs<AppResult.Failed>(it.current(parent)).failure
            assertEquals(FailureKind.AccessDenied, failure.kind)
            assertEquals("collaboration_access_denied", failure.code)
        }
        withRepository(MockEngine { respond(" ".repeat(786_433), headers = headers) }) {
            assertIs<AppResult.Failed>(it.contacts(owner, "", null, 30))
        }
        withRepository(MockEngine { respond(" ".repeat(4097), headers = headers) }) {
            assertIs<AppResult.Failed>(it.current(parent))
        }
    }

    @Test
    fun cancelledAcquisitionReleasesActualHttpHandler() = runTest {
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
        ) {
            val pending = async { it.current(parent) }
            entered.await()
            pending.cancelAndJoin()
            stopped.await()
            assertTrue(pending.isCancelled)
        }
    }

    private suspend fun withRepository(
        engine: MockEngine,
        action: suspend (RemoteCollaborationSpaceRepository) -> Unit,
    ) {
        val client =
            createHttpClient(engine, HttpClientSettings(SETUP_BASE)) {
                install(SessionAuthentication) {
                    origin = Url(SETUP_BASE)
                    acquire = { session, rejected ->
                        assertEquals(parent.sessionId, session)
                        assertNull(rejected)
                        AppResult.Success("fictional-native")
                    }
                }
            }
        try {
            action(
                RemoteCollaborationSpaceRepository(
                    ApiCollaborationSpaceRemoteSource(CollaborationSpaceApi(client)),
                    IdentityServer(SETUP_BASE, "Fictional Android"),
                )
            )
        } finally {
            client.close()
        }
    }
}
