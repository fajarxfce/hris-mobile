package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/** Actual parent and finite metadata child over inert SDK handles and an observed HTTP engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationRuntimeProviderHistoryTest {
    @Test
    fun boundedPagePreservesOriginalOwnerPositionsAndProviderOrderWithoutOpeningARoom() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.providerRequest = {
            assertEquals("b", it.url.parameters["dir"])
            assertEquals("20", it.url.parameters["limit"])
            assertEquals("from/opaque", it.url.parameters["from"])
            assertEquals("until/opaque", it.url.parameters["to"])
            assertEquals(
                "Bearer ${f.currentSession.accessToken}",
                it.headers[HttpHeaders.Authorization],
            )
            respond(PAGE)
        }
        try {
            f.lease.completeAcquisition()
            val page =
                assertIs<AppResult.Success<CollaborationProviderHistoryPage>>(
                        f.lease.readProviderHistory(ROOM, "from/opaque", "until/opaque")
                    )
                    .value
            assertEquals(f.lease.owner, page.owner)
            assertEquals(f.lease.deviceId, page.deviceId)
            assertEquals(ROOM, page.roomId)
            assertEquals("from/opaque", page.requestedPosition)
            assertEquals("until/opaque", page.requestedUntilPosition)
            assertEquals("from/opaque", page.start)
            assertEquals("next/opaque", page.next)
            assertEquals(listOf("\$new", "\$old"), page.events.map { it.eventId })
            assertEquals(listOf(1L, 2L), page.events.map { it.timestamp })
            assertEquals(1, f.providerRequests)
            assertEquals(1, f.sessionReads)
            assertEquals(0, f.syncRequests)
            assertTrue(f.calls.isEmpty())
        } finally {
            f.close()
        }
        assertEquals(1, f.calls.count { it == "client.close" })
    }

    @Test
    fun eachPageUsesCurrentSdkCredentialsAndInvalidBoundsNeverReachTransport() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.providerRequest = {
            assertEquals(
                "Bearer ${f.currentSession.accessToken}",
                it.headers[HttpHeaders.Authorization],
            )
            respond("""{"start":"head","chunk":[]}""")
        }
        try {
            f.lease.completeAcquisition()
            repeat(2) {
                f.currentSession = f.currentSession.copy(accessToken = "fictional-token-$it")
                val page =
                    assertIs<AppResult.Success<CollaborationProviderHistoryPage>>(
                            f.lease.readProviderHistory(ROOM, null)
                        )
                        .value
                assertNull(page.next)
                assertTrue(page.events.isEmpty())
            }
            for (invalid in listOf("", "with space", "x".repeat(4097))) {
                assertIs<AppResult.Failed>(f.lease.readProviderHistory(ROOM, invalid))
                assertIs<AppResult.Failed>(f.lease.readProviderHistory(ROOM, null, invalid))
            }
            assertEquals(2, f.sessionReads)
            assertEquals(2, f.providerRequests)
            assertEquals(0, f.syncRequests)
        } finally {
            f.close()
        }
    }

    @Test
    fun parentDisposalCancelsThePageBeforeClosingItsClientAndDatabase() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        f.providerRequest = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                f.calls += "page.stopped"
            }
        }
        try {
            f.lease.completeAcquisition()
            val pending = async { f.lease.readProviderHistory(ROOM, null) }
            entered.await()
            assertEquals(AppResult.Success(Unit), f.lease.close())
            assertIs<AppResult.Failed>(pending.await())
            assertTrue(f.calls.indexOf("page.stopped") < f.calls.indexOf("client.close"))
            assertTrue(f.calls.indexOf("client.close") < f.calls.indexOf("directory.close"))
            assertIs<AppResult.Failed>(f.lease.readProviderHistory(ROOM, null))
            assertEquals(1, f.providerRequests)
        } finally {
            f.close()
        }
    }

    @Test
    fun callerCancellationStopsOnlyItsPageAndASeparateReadStillSucceeds() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.providerRequest = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        try {
            f.lease.completeAcquisition()
            val pending = async { f.lease.readProviderHistory(ROOM, null) }
            entered.await()
            pending.cancelAndJoin()
            stopped.await()
            assertTrue(pending.isCancelled)
            assertFalse(f.lease.closingRequested)
            assertFalse("client.close" in f.calls)
            f.providerRequest = { respond("""{"start":"head","chunk":[]}""") }
            assertIs<AppResult.Success<*>>(f.lease.readProviderHistory(ROOM, null))
            assertEquals(2, f.providerRequests)
            assertEquals(0, f.syncRequests)
        } finally {
            f.close()
        }
    }

    @Test
    fun credentialDenialCannotBecomeAnEmptyPageOrTriggerARetry() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
            f.providerRequest = { respond("{}", status) }
            try {
                f.lease.completeAcquisition()
                val failure =
                    assertIs<AppResult.Failed>(f.lease.readProviderHistory(ROOM, null)).failure
                assertEquals(
                    if (status == HttpStatusCode.Unauthorized) FailureKind.Unauthorized
                    else FailureKind.AccessDenied,
                    failure.kind,
                )
                assertEquals(1, f.providerRequests)
                assertEquals(0, f.syncRequests)
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun credentialRevocationBeforePublicationSuppressesASuccessfulPage() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.providerRequest = {
            f.persistence.reportAuthenticationFailure()
            respond(PAGE)
        }
        try {
            f.lease.completeAcquisition()
            val failure =
                assertIs<AppResult.Failed>(
                        f.lease.readProviderHistory(ROOM, "from/opaque", "until/opaque")
                    )
                    .failure
            assertSame(f.lease.credentialFailures.value, failure)
            assertEquals(1, f.providerRequests)
            assertEquals(0, f.syncRequests)
        } finally {
            f.close()
        }
    }

    private companion object {
        const val ROOM = "!room:test"
        const val PAGE =
            """{"start":"from/opaque","end":"next/opaque","chunk":[{"event_id":"${'$'}new","sender":"@sender:test","origin_server_ts":1,"type":"m.room.encrypted"},{"event_id":"${'$'}old","sender":"@sender:test","origin_server_ts":2,"type":"m.room.redaction"}]}"""
    }
}
