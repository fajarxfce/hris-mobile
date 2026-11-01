package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationHistoryApi
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

class AndroidCollaborationHistoryReadsTest {
    private val home = "https://gateway.example.test/"
    private val room = "!room:test"
    private val user = "@fictional:test"
    private val device = "FICTIONAL"

    private fun session(token: String = "current-fictional-token") =
        Session(token, "refresh", user, device, home, null, SlidingSyncVersion.NONE)

    @Test
    fun invalidPagePositionsNeverReadSdkCredentialsOrStartTransport() = runTest {
        var sessions = 0
        val http = createHttpClient(MockEngine { error("No invalid-position transport") }, home)
        val children = SdkChildResources(this)
        val sdk =
            object : Client(NoHandle) {
                override fun session(): Session {
                    sessions++
                    return this@AndroidCollaborationHistoryReadsTest.session()
                }
            }
        val reads =
            AndroidCollaborationHistoryReads(
                sdk,
                user,
                device,
                home,
                children,
                this,
                CollaborationHistoryApi(http, CollaborationSdkEndpoint(home)),
            ) {
                null
            }
        try {
            for (position in listOf("", "bad\nposition", "é", "x".repeat(4097))) {
                assertEquals(
                    FailureKind.Validation,
                    assertIs<AppResult.Failed>(reads.page(room, position)).failure.kind,
                )
            }
            assertEquals(0, sessions)
        } finally {
            children.close()
            http.cancelAndClose()
        }
    }

    @Test
    fun parentCancellationStopsAProviderPageAndPreservesTheBorrowedPool() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var requests = 0
        val http =
            createHttpClient(
                MockEngine {
                    requests++
                    assertEquals("opaque-position", it.url.parameters["from"])
                    assertEquals("20", it.url.parameters["limit"])
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                },
                home,
            )
        val children = SdkChildResources(this)
        val sdk =
            object : Client(NoHandle) {
                override fun session() = this@AndroidCollaborationHistoryReadsTest.session()
            }
        val reads =
            AndroidCollaborationHistoryReads(
                sdk,
                user,
                device,
                home,
                children,
                this,
                CollaborationHistoryApi(http, CollaborationSdkEndpoint(home)),
            ) {
                null
            }
        try {
            val pending = async { reads.page(room, "opaque-position") }
            entered.await()
            assertEquals(AppResult.Success(Unit), children.close())
            assertIs<AppResult.Failed>(pending.await())
            stopped.await()
            assertEquals(1, requests)
            assertTrue(http.coroutineContext.isActive)
        } finally {
            children.close()
            http.cancelAndClose()
        }
    }

    @Test
    fun pageDecodingCannotPublishPastLateRuntimeDenial() = runTest {
        val denied = Failure(FailureKind.AccessDenied, "Denied", code = "permission_denied")
        var failure: Failure? = null
        val http =
            createHttpClient(
                MockEngine {
                    failure = denied
                    respond("""{"start":"opaque","chunk":[]}""")
                },
                home,
            )
        val children = SdkChildResources(this)
        val sdk =
            object : Client(NoHandle) {
                override fun session() = this@AndroidCollaborationHistoryReadsTest.session()
            }
        val reads =
            AndroidCollaborationHistoryReads(
                sdk,
                user,
                device,
                home,
                children,
                this,
                CollaborationHistoryApi(http, CollaborationSdkEndpoint(home)),
            ) {
                failure
            }
        try {
            assertEquals(AppResult.Failed(denied), reads.page(room, null))
        } finally {
            children.close()
            http.cancelAndClose()
        }
    }

    @Test
    fun currentSdkTokenIsReadPerRequestAndBorrowedTransportSurvivesChildAndParentClose() = runTest {
        var token = "first-token"
        var requests = 0
        val http =
            createHttpClient(
                MockEngine {
                    requests++
                    assertEquals("Bearer $token", it.headers[HttpHeaders.Authorization])
                    respond("""{"chunk":[{"event_id":"${'$'}latest"}]}""")
                },
                home,
            )
        val children = SdkChildResources(this)
        val sdk =
            object : Client(NoHandle) {
                override fun session() = session(token)
            }
        val heads =
            AndroidCollaborationHistoryReads(
                sdk,
                user,
                device,
                home,
                children,
                this,
                CollaborationHistoryApi(http, CollaborationSdkEndpoint(home)),
            ) {
                null
            }
        try {
            assertEquals(AppResult.Success("${'$'}latest"), heads.read(room))
            token = "rotated-token"
            assertEquals(AppResult.Success("${'$'}latest"), heads.read(room))
            assertEquals(AppResult.Success(Unit), children.close())
            assertTrue(http.coroutineContext.isActive)
            assertIs<AppResult.Failed>(heads.read(room))
            assertEquals(2, requests)
        } finally {
            children.close()
            http.cancelAndClose()
        }
    }

    @Test
    fun wrongIdentityOrUnsafeBearerNeverStartsHttp() = runTest {
        val invalid =
            listOf(
                session().copy(userId = "@foreign:test"),
                session().copy(deviceId = "foreign"),
                session().copy(homeserverUrl = "https://foreign.example.test/"),
                session("bad\ntoken"),
                session("x".repeat(8193)),
            )
        val http =
            createHttpClient(MockEngine { error("Identity must be checked before HTTP") }, home)
        try {
            for (value in invalid) {
                val children = SdkChildResources(this)
                val sdk =
                    object : Client(NoHandle) {
                        override fun session() = value
                    }
                val heads =
                    AndroidCollaborationHistoryReads(
                        sdk,
                        user,
                        device,
                        home,
                        children,
                        this,
                        CollaborationHistoryApi(http, CollaborationSdkEndpoint(home)),
                    ) {
                        null
                    }
                assertIs<AppResult.Failed>(heads.read(room))
                assertEquals(AppResult.Success(Unit), children.close())
            }
        } finally {
            http.cancelAndClose()
        }
    }

    @Test
    fun callerAndParentCancellationStopPendingHttpWithoutClosingItsPoolOrRetrying() = runTest {
        for (parent in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            var requests = 0
            val http =
                createHttpClient(
                    MockEngine {
                        requests++
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    },
                    home,
                )
            val children = SdkChildResources(this)
            val sdk =
                object : Client(NoHandle) {
                    override fun session() = this@AndroidCollaborationHistoryReadsTest.session()
                }
            val heads =
                AndroidCollaborationHistoryReads(
                    sdk,
                    user,
                    device,
                    home,
                    children,
                    this,
                    CollaborationHistoryApi(http, CollaborationSdkEndpoint(home)),
                ) {
                    null
                }
            try {
                val pending = async { heads.read(room) }
                entered.await()
                if (parent) {
                    assertEquals(AppResult.Success(Unit), children.close())
                    assertIs<AppResult.Failed>(pending.await())
                } else pending.cancelAndJoin()
                stopped.await()
                assertEquals(1, requests)
                assertTrue(http.coroutineContext.isActive)
            } finally {
                children.close()
                http.cancelAndClose()
            }
        }
    }

    @Test
    fun httpTokenExpiryIsSdkFailureAndLateRestrictionWinsBeforePublishingAnAnchor() = runTest {
        for (denied in listOf(false, true)) {
            var failure: Failure? = null
            val restriction =
                Failure(FailureKind.AccessDenied, "Restricted", code = "permission_denied")
            val http =
                createHttpClient(
                    MockEngine {
                        if (denied) {
                            failure = restriction
                            respond("""{"chunk":[{"event_id":"${'$'}latest"}]}""")
                        } else respond("{}", HttpStatusCode.Unauthorized)
                    },
                    home,
                )
            val children = SdkChildResources(this)
            val sdk =
                object : Client(NoHandle) {
                    override fun session() = this@AndroidCollaborationHistoryReadsTest.session()
                }
            val heads =
                AndroidCollaborationHistoryReads(
                    sdk,
                    user,
                    device,
                    home,
                    children,
                    this,
                    CollaborationHistoryApi(http, CollaborationSdkEndpoint(home)),
                ) {
                    failure
                }
            try {
                val rejected = assertIs<AppResult.Failed>(heads.read(room)).failure
                if (denied) assertEquals(restriction, rejected)
                else {
                    assertEquals(FailureKind.Unauthorized, rejected.kind)
                    assertEquals("client.collaboration_sdk_unauthorized", rejected.code)
                }
                assertEquals(AppResult.Success(Unit), children.close())
            } finally {
                children.close()
                http.cancelAndClose()
            }
        }
    }
}
