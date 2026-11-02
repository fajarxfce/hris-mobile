package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationPusherApi
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

class AndroidCollaborationPusherTest {
    @Test
    fun fixedEmptyVendorRequestUsesTheLatestSdkBearerAndLeavesTheBorrowedPoolAlive() = runTest {
        val tokens = listOf("first-token", "rotated-token")
        var requests = 0
        val f =
            Fixture(this) { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(
                    "https://gateway.example.test/_matrix/client/unstable/dev.fajar.hris/pusher",
                    request.url.toString(),
                )
                assertEquals(
                    "Bearer ${tokens[requests++]}",
                    request.headers[HttpHeaders.Authorization],
                )
                assertNull(request.headers[HttpHeaders.Cookie])
                assertTrue(request.body.toByteArray().isEmpty())
                respond(" {} ")
            }
        try {
            for (token in tokens) {
                f.session = f.session.copy(accessToken = token)
                assertEquals(AppResult.Success(Unit), f.pusher.activate())
            }
            assertEquals(2, requests)
            assertEquals(AppResult.Success(Unit), f.children.close())
            assertTrue(f.http.coroutineContext.isActive)
            assertIs<AppResult.Failed>(f.pusher.activate())
            assertEquals(2, requests)
        } finally {
            f.close()
        }
    }

    @Test
    fun foreignSdkIdentityAndUnsafeBearerStopBeforeTransport() = runTest {
        val f = Fixture(this) { error("Invalid identity cannot start transport") }
        try {
            val original = f.session
            for (invalid in
                listOf(
                    original.copy(userId = "@other:test"),
                    original.copy(deviceId = "OTHER"),
                    original.copy(homeserverUrl = "https://foreign.example.test/"),
                    original.copy(accessToken = "bad\ntoken"),
                    original.copy(accessToken = ""),
                    original.copy(accessToken = "x".repeat(8193)),
                )) {
                f.session = invalid
                assertIs<AppResult.Failed>(f.pusher.activate())
            }
        } finally {
            f.close()
        }
    }

    @Test
    fun malformedOrOversizedAcknowledgmentsAndRedirectsAreNeverSuccessfulOrRetried() = runTest {
        val replies = listOf("[]", "null", "{\"unexpected\":true}", "{}{}", " ".repeat(1025), "")
        var response = ""
        var status = HttpStatusCode.OK
        var requests = 0
        val f =
            Fixture(this) {
                requests++
                respond(
                    response,
                    status,
                    headersOf(HttpHeaders.Location, "https://foreign.example.test/"),
                )
            }
        try {
            for (body in replies) {
                response = body
                assertEquals(
                    FailureKind.Unexpected,
                    assertIs<AppResult.Failed>(f.pusher.activate()).failure.kind,
                )
            }
            response = "{}"
            for (code in listOf(HttpStatusCode.NoContent, HttpStatusCode.Found)) {
                status = code
                assertEquals(
                    FailureKind.Unexpected,
                    assertIs<AppResult.Failed>(f.pusher.activate()).failure.kind,
                )
            }
            assertEquals(replies.size + 2, requests)
        } finally {
            f.close()
        }
    }

    @Test
    fun expiredOrForbiddenGatewayCredentialsRemainClassifiedWithoutAutomaticRefresh() = runTest {
        var status = HttpStatusCode.Unauthorized
        var requests = 0
        val f =
            Fixture(this) {
                requests++
                respond("{}", status)
            }
        try {
            assertEquals(
                "client.collaboration_sdk_unauthorized",
                assertIs<AppResult.Failed>(f.pusher.activate()).failure.code,
            )
            status = HttpStatusCode.Forbidden
            assertEquals(
                FailureKind.AccessDenied,
                assertIs<AppResult.Failed>(f.pusher.activate()).failure.kind,
            )
            status = HttpStatusCode.ServiceUnavailable
            assertEquals(
                FailureKind.Service,
                assertIs<AppResult.Failed>(f.pusher.activate()).failure.kind,
            )
            assertEquals(3, requests)
        } finally {
            f.close()
        }
    }

    @Test
    fun callerAndRuntimeCancellationStopTheExactPendingRequest() = runTest {
        for (parent in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            var requests = 0
            val f =
                Fixture(this) {
                    requests++
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            try {
                val pending = async { f.pusher.activate() }
                entered.await()
                if (parent) {
                    assertEquals(AppResult.Success(Unit), f.children.close())
                    assertIs<AppResult.Failed>(pending.await())
                } else pending.cancelAndJoin()
                stopped.await()
                assertEquals(1, requests)
                assertTrue(f.http.coroutineContext.isActive)
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun pendingActivationRejectsAnotherWaiterAndItsDeadlineAllowsAnExplicitRetry() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var requests = 0
        val f =
            Fixture(this) {
                if (++requests == 1) {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                } else respond("{}")
            }
        try {
            val pending = async { f.pusher.activate() }
            entered.await()
            assertEquals(
                "client.collaboration_pusher_busy",
                assertIs<AppResult.Failed>(f.pusher.activate()).failure.code,
            )
            assertEquals(1, requests)
            advanceTimeBy(10_001)
            assertEquals(
                FailureKind.Timeout,
                assertIs<AppResult.Failed>(pending.await()).failure.kind,
            )
            stopped.await()
            assertEquals(AppResult.Success(Unit), f.pusher.activate())
            assertEquals(2, requests)
        } finally {
            f.close()
        }
    }

    @Test
    fun lateRuntimeDenialWinsOverSuccessAndTransportFailureAndStopsFurtherRequests() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.ServiceUnavailable)) {
            var denial: Failure? = null
            val denied = Failure(FailureKind.AccessDenied, "Denied", code = "permission_denied")
            var requests = 0
            val f =
                Fixture(this, { denial }) {
                    requests++
                    denial = denied
                    respond("{}", status)
                }
            try {
                assertEquals(AppResult.Failed(denied), f.pusher.activate())
                assertEquals(AppResult.Failed(denied), f.pusher.activate())
                assertEquals(1, requests)
            } finally {
                f.close()
            }
        }
    }

    private class Fixture(
        lifetime: CoroutineScope,
        failure: () -> Failure? = { null },
        handler: MockRequestHandler,
    ) {
        private val home = "https://gateway.example.test/"
        var session =
            Session(
                "first-token",
                "refresh",
                "@fictional:test",
                "FICTIONAL",
                home,
                null,
                SlidingSyncVersion.NONE,
            )
        // The transport and deadline must share virtual time. A Default-dispatched mock can
        // finish after runTest has already advanced the waiting caller's clock by ten seconds.
        val http =
            createHttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher =
                            lifetime.coroutineContext[ContinuationInterceptor]
                                as CoroutineDispatcher
                        addHandler(handler)
                    }
                ),
                home,
            )
        val children = SdkChildResources(lifetime)
        private val sdk =
            object : Client(NoHandle) {
                override fun session() = this@Fixture.session
            }
        val pusher =
            AndroidCollaborationPusher(
                sdk,
                session.userId,
                session.deviceId,
                home,
                children,
                lifetime,
                CollaborationPusherApi(http, CollaborationSdkEndpoint(home)),
                failure,
            )

        suspend fun close() {
            assertEquals(AppResult.Success(Unit), children.close())
            http.cancelAndClose()
        }
    }
}
