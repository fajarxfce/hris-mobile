package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationConversationApi
import dev.fajar.starter.collaboration.data.datasources.ApiCollaborationConversationRemoteSource
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationTransportRepository
import dev.fajar.starter.collaboration.domain.entities.CollaborationDirectTransport
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

private val transportJson =
    """{"companyId":"${setupOwner.companyId}","accountId":"${setupOwner.accountId}","nativeSessionId":"${setupOwner.nativeSessionId}","deviceId":"$SETUP_DEVICE","deviceVersion":1,"platform":"ANDROID","conversationId":"$CONVERSATION_ID","firstAccountId":"${setupOwner.accountId}","secondAccountId":"$PEER_ID","matrixRoomId":"!opaque-v12-room","roomProvisioningVersion":0,"recipientReady":true}"""

class CollaborationDirectTransportTest {
    @Test
    fun eachReadUsesOneBoundedNativeRequestAndDoesNotCacheAdmission() = runTest {
        var attempts = 0
        withTransport(
            MockEngine { request ->
                attempts++
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(
                    "/api/v1/companies/${setupOwner.companyId}/collaboration/direct-conversations/$CONVERSATION_ID/transport",
                    request.url.encodedPath,
                )
                assertEquals("Bearer fictional-native", request.headers[HttpHeaders.Authorization])
                if (attempts == 1)
                    respond(
                        transportJson,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                else
                    respond(
                        """{"code":"company_module_disabled"}""",
                        HttpStatusCode.Forbidden,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
            }
        ) { repo ->
            val value =
                assertIs<AppResult.Success<CollaborationDirectTransport>>(
                        repo.read(setupOwner, CONVERSATION_ID)
                    )
                    .value
            assertEquals(setupOwner, value.owner)
            assertEquals(SETUP_DEVICE, value.deviceId)
            assertEquals("!opaque-v12-room", value.matrixRoomId)
            val denied = assertIs<AppResult.Failed>(repo.read(setupOwner, CONVERSATION_ID))
            assertEquals(FailureKind.AccessDenied, denied.failure.kind)
            assertEquals(2, attempts)
        }
    }

    @Test
    fun recipientReadinessIsAnExplicitFactAndMissingFieldCannotAdmitDelivery() = runTest {
        for (ready in listOf(false, true)) withTransport(
            MockEngine {
                respond(
                    transportJson.replace("\"recipientReady\":true", "\"recipientReady\":$ready"),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        ) { repo ->
            assertEquals(
                ready,
                assertIs<AppResult.Success<CollaborationDirectTransport>>(
                        repo.read(setupOwner, CONVERSATION_ID)
                    )
                    .value
                    .recipientReady,
            )
        }
        withTransport(
            MockEngine {
                respond(
                    transportJson.replace(",\"recipientReady\":true", ""),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.read(setupOwner, CONVERSATION_ID))
        }
    }

    @Test
    fun invalidOwnersAndIdentifiersNeverDispatch() = runTest {
        withTransport(MockEngine { error("Invalid input must not dispatch") }) { repo ->
            assertIs<AppResult.Failed>(
                repo.read(setupOwner.copy(audience = "https://other.test/"), CONVERSATION_ID)
            )
            assertIs<AppResult.Failed>(repo.read(setupOwner, "../escape"))
            assertIs<AppResult.Failed>(
                repo.read(setupOwner.copy(nativeSessionId = "invalid"), CONVERSATION_ID)
            )
        }
    }

    @Test
    fun responseCannotSubstituteOwnerConversationPlatformOrRoom() = runTest {
        for (body in
            listOf(
                transportJson.replace(setupOwner.companyId, SETUP_JOB),
                transportJson.replace(setupOwner.nativeSessionId, SETUP_JOB),
                transportJson.replace(setupOwner.accountId, SETUP_JOB),
                transportJson.replace(CONVERSATION_ID, SETUP_JOB),
                transportJson.replace("ANDROID", "WEB"),
                transportJson.replace("!opaque-v12-room", "!bad room"),
                transportJson.replace("\"deviceVersion\":1", "\"deviceVersion\":-1"),
                transportJson.replace(
                    "\"roomProvisioningVersion\":0",
                    "\"roomProvisioningVersion\":1000000",
                ),
                transportJson.replace(PEER_ID, setupOwner.accountId),
                " ".repeat(2049) + transportJson,
            )) withTransport(
            MockEngine {
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.read(setupOwner, CONVERSATION_ID))
        }
    }

    @Test
    fun cancelledReadStopsItsOriginalRequestWithoutAnotherAttempt() = runTest {
        var attempts = 0
        val disposed = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        withTransport(
            MockEngine {
                attempts++
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    disposed.complete(Unit)
                }
            }
        ) { repo ->
            val call = launch {
                repo.read(setupOwner, CONVERSATION_ID)
                fail("Cancelled read cannot publish")
            }
            entered.await()
            call.cancelAndJoin()
            // The engine owns its request coroutine independently of the caller's job.
            disposed.await()
            assertTrue(call.isCancelled)
            assertEquals(1, attempts)
        }
    }

    private suspend fun withTransport(
        engine: MockEngine,
        action: suspend (RemoteCollaborationTransportRepository) -> Unit,
    ) {
        val client =
            createHttpClient(engine, HttpClientSettings(SETUP_BASE)) {
                install(SessionAuthentication) {
                    origin = Url(SETUP_BASE)
                    acquire = { session, _ ->
                        assertEquals(setupOwner.sessionId, session)
                        AppResult.Success("fictional-native")
                    }
                }
            }
        try {
            action(
                RemoteCollaborationTransportRepository(
                    ApiCollaborationConversationRemoteSource(CollaborationConversationApi(client)),
                    IdentityServer(SETUP_BASE, "Fictional Android"),
                )
            )
        } finally {
            client.close()
        }
    }
}
