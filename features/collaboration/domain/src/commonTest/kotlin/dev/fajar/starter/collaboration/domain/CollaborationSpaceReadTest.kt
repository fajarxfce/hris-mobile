@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceReadTest {
    @Test
    fun discoveryAndTerminalPageIgnoreSelectedCompanyAndKeepRetainedDenial() = runTest {
        val f = CollaborationSpaceFixture()
        f.setup.workspace = null
        f.setup.beforeWorkspace = { error("No selected-company acquisition") }
        val denied = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
        f.setup.admission.deny(denied)
        val discovery =
            assertIs<AppResult.Success<CollaborationSpaceDiscovery>>(f.discovery()(f.parent)).value
        assertEquals(f.owner, discovery.owner)
        assertEquals(f.setup.now + 60.seconds, discovery.expiresAt)
        val page =
            assertIs<AppResult.Success<CollaborationSpaceContactPage>>(
                    f.contacts()(f.owner, "  peer_%  ")
                )
                .value
        assertNull(page.nextCursor)
        assertEquals("peer_%", f.query)
        advanceUntilIdle()
        assertEquals(1, f.discoveryCalls)
        assertEquals(1, f.pageCalls)
        assertEquals(denied, f.setup.admission.gate.denial)
    }

    @Test
    fun missingReplacedExpiredOrUnsupportedParentsStopBothRequests() = runTest {
        for (mode in 0..7) {
            val f = CollaborationSpaceFixture()
            f.setup.session =
                when (mode) {
                    0 -> null
                    1 -> f.session.copy(id = "replaced")
                    2 -> f.session.copy(user = f.session.user.copy(id = f.contact.accountId))
                    3 ->
                        f.session.copy(binding = f.session.binding.copy(serverId = f.owner.spaceId))
                    4 ->
                        f.session.copy(
                            binding =
                                f.session.binding.copy(audience = "https://foreign.example.test/")
                        )
                    5 ->
                        f.session.copy(
                            binding =
                                f.session.binding.copy(
                                    expiresAtEpochMillis = f.setup.now.toEpochMilliseconds()
                                )
                        )
                    else -> f.session
                }
            if (mode == 6) f.setup.sessionFailure = Failure(FailureKind.Storage, "Unavailable")
            val platform = if (mode == 7) AppPlatform.Desktop else AppPlatform.Android
            assertIs<AppResult.Failed>(f.discovery(platform)(f.parent))
            assertIs<AppResult.Failed>(f.contacts(platform)(f.owner))
            assertEquals(0, f.discoveryCalls + f.pageCalls)
        }
    }

    @Test
    fun successfulResponsesFromOldSessionAreDiscardedAndFailuresRemainClassified() = runTest {
        val f = CollaborationSpaceFixture()
        f.onDiscovery = {
            f.setup.session = f.session.copy(id = "replacement")
            AppResult.Success(f.observation)
        }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.discovery()(f.parent)).failure.kind,
        )
        f.setup.session = f.session
        f.onPage = {
            f.setup.session =
                f.session.copy(binding = f.session.binding.copy(serverId = f.owner.spaceId))
            AppResult.Success(f.page)
        }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.contacts()(f.owner)).failure.kind,
        )
        f.setup.session = f.session
        val denied =
            Failure(FailureKind.AccessDenied, "Denied", code = "collaboration_access_denied")
        f.onDiscovery = {
            f.setup.sessionFailure = Failure(FailureKind.Storage, "Unavailable")
            AppResult.Failed(denied)
        }
        assertEquals(denied, assertIs<AppResult.Failed>(f.discovery()(f.parent)).failure)
    }

    @Test
    fun cancellationAndFiniteTimeoutOwnBothRequestLifetimes() = runTest {
        for (page in listOf(false, true)) {
            val f = CollaborationSpaceFixture()
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            suspend fun pending(): Nothing =
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            f.onDiscovery = { pending() }
            f.onPage = { pending() }
            val request = async { if (page) f.contacts()(f.owner) else f.discovery()(f.parent) }
            entered.await()
            request.cancelAndJoin()
            stopped.await()
            assertTrue(request.isCancelled)
            val timeout = if (page) f.contacts()(f.owner) else f.discovery()(f.parent)
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(timeout).failure.kind)
        }
    }
}
