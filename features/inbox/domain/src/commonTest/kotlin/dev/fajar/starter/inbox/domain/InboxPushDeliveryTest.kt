@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.push.entities.*
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class InboxPushDeliveryTest {
    @Test
    fun canonicalSyncedUnreadMessageUsesGenericCopyAndAScopedInternalTargetOnlyOnce() = runTest {
        val f = InboxPushDeliveryFixture()
        val work = assertNotNull(assertIs<AppResult.Success<PreparedInboxPush?>>(f.prepare()).value)
        assertEquals(1, f.state.pending.single().preparations)
        assertEquals(AppResult.Success(Unit), f.present(work, f.copy))
        val notification = f.shown.single()
        assertEquals(f.copy.title, notification.title)
        assertEquals(f.copy.body, notification.body)
        assertEquals(
            "accounts/${f.accountId}/companies/${f.companyId}/inbox/${f.itemId}",
            notification.destination,
        )
        assertTrue(f.state.pending.isEmpty())
        assertEquals(f.owner, f.state.attempts.single().owner)
        assertEquals(AppResult.Success(Unit), f.present(work, f.copy))
        assertEquals(1, f.shown.size)
        assertEquals(AppResult.Success(null), f.prepare())
    }

    @Test
    fun unavailableCanonicalOrUnsynchronizedLocalDataCannotClaimOrDisplay() = runTest {
        val f = InboxPushDeliveryFixture()
        val work = requireNotNull((f.prepare() as AppResult.Success).value)
        f.inbox.snapshot.value = AppResult.Success(InboxCachePage(null, emptyList(), null))
        assertEquals(
            "client.inbox_updates_pending",
            assertIs<AppResult.Failed>(f.present(work, f.copy)).failure.code,
        )
        assertTrue(f.state.attempts.isEmpty())
        assertTrue(f.shown.isEmpty())
        f.inbox.itemCall = { AppResult.Failed(Failure(FailureKind.Network, "Unavailable")) }
        assertIs<AppResult.Failed>(f.present(work, f.copy))
        assertEquals(1, f.state.pending.size)
        assertTrue(f.shown.isEmpty())
    }

    @Test
    fun deniedRemoteAccessStaysGatedWhenCleanupFailsAndAPlainRetryCannotReopenIt() = runTest {
        val f = InboxPushDeliveryFixture()
        val work = requireNotNull((f.prepare() as AppResult.Success).value)
        val denied = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
        f.inbox.itemCall = { AppResult.Failed(denied) }
        f.inbox.restrictionCall = { AppResult.Failed(Failure(FailureKind.Storage, "Unavailable")) }
        assertEquals(AppResult.Failed(denied), f.present(work, f.copy))
        f.inbox.itemCall = { AppResult.Success(f.item) }
        assertEquals(AppResult.Failed(denied), f.present(work, f.copy))
        assertEquals(1, f.inbox.itemCalls)
        assertTrue(f.state.attempts.isEmpty())
        assertTrue(f.shown.isEmpty())
    }

    @Test
    fun readMessagesDeniedOsAccessAndDisabledRegistrationNeverDisplay() = runTest {
        for (mode in 0..2) {
            val f = InboxPushDeliveryFixture()
            val work = requireNotNull((f.prepare() as AppResult.Success).value)
            when (mode) {
                0 -> f.inbox.itemCall = { AppResult.Success(f.item.copy(readAt = f.now)) }
                1 -> f.permission = NotificationAccess.Denied
                2 -> f.pushState = f.pushState.copy(desired = false)
            }
            assertEquals(AppResult.Success(Unit), f.present(work, f.copy))
            assertTrue(f.shown.isEmpty())
            assertTrue(f.state.pending.isEmpty())
            assertEquals(1, f.state.attempts.size)
        }
    }

    @Test
    fun disablingPushWhilePermissionCheckIsPendingCannotDisplayItsStalePreference() = runTest {
        val f = InboxPushDeliveryFixture()
        val work = requireNotNull((f.prepare() as AppResult.Success).value)
        val release = CompletableDeferred<Unit>()
        f.beforePermission = { release.await() }
        val operation = async { f.present(work, f.copy) }
        runCurrent()
        f.pushState = f.pushState.copy(desired = false)
        release.complete(Unit)
        assertEquals(AppResult.Success(Unit), operation.await())
        assertTrue(f.shown.isEmpty())
    }

    @Test
    fun replacementExpiryAndDenialDuringTheFinalPreferenceReadCannotDisplay() = runTest {
        for (mode in 0..2) {
            val f = InboxPushDeliveryFixture()
            val work = requireNotNull((f.prepare() as AppResult.Success).value)
            val release = CompletableDeferred<Unit>()
            var reads = 0
            f.beforePushRead = {
                reads++
                if (reads == 4) release.await()
            }
            val operation = async { f.present(work, f.copy) }
            runCurrent()
            assertEquals(4, reads)
            when (mode) {
                0 ->
                    f.inbox.sessionState.value =
                        AppResult.Success(f.session.copy(id = "replacement"))
                1 -> f.now += 31.seconds
                2 ->
                    f.inbox.cache.restrict(
                        f.access,
                        Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required"),
                    )
            }
            release.complete(Unit)
            assertIs<AppResult.Failed>(operation.await())
            assertTrue(f.shown.isEmpty())
            assertEquals(1, f.state.attempts.size)
        }
    }

    @Test
    fun replacementAccountAndAnExpiredPreparationCannotPublishLateCanonicalReads() = runTest {
        val f = InboxPushDeliveryFixture()
        val work = requireNotNull((f.prepare() as AppResult.Success).value)
        val release = CompletableDeferred<Unit>()
        f.inbox.itemCall = {
            withContext(NonCancellable) { release.await() }
            AppResult.Success(f.item)
        }
        val operation = async { f.present(work, f.copy) }
        runCurrent()
        f.inbox.sessionState.value = AppResult.Success(f.session.copy(id = "replacement"))
        release.complete(Unit)
        assertIs<AppResult.Failed>(operation.await())
        assertTrue(f.shown.isEmpty())
        assertTrue(f.state.attempts.isEmpty())
        f.inbox.sessionState.value = AppResult.Success(f.session)
        f.now += 31.seconds
        assertEquals(
            "client.inbox_push_expired",
            assertIs<AppResult.Failed>(f.present(work, f.copy)).failure.code,
        )
        assertTrue(f.shown.isEmpty())
    }

    @Test
    fun storageFailurePreventsDisplayAndAnUncertainCancelledOsAttemptIsNeverRepeated() = runTest {
        val f = InboxPushDeliveryFixture()
        val work = requireNotNull((f.prepare() as AppResult.Success).value)
        f.failWrite = true
        assertIs<AppResult.Failed>(f.present(work, f.copy))
        assertTrue(f.shown.isEmpty())
        f.failWrite = false
        val release = CompletableDeferred<Unit>()
        f.beforeShow = { withContext(NonCancellable) { release.await() } }
        val operation = launch { f.present(work, f.copy) }
        runCurrent()
        assertEquals(1, f.shown.size)
        operation.cancel()
        release.complete(Unit)
        operation.join()
        assertEquals(AppResult.Success(Unit), f.present(work, f.copy))
        assertEquals(1, f.shown.size)
        assertTrue(f.state.pending.isEmpty())
    }

    @Test
    fun boundedPreparationsFenceOlderClaimsAndNeverSwitchTheSelectedCompany() = runTest {
        val f = InboxPushDeliveryFixture()
        val first = requireNotNull((f.prepare() as AppResult.Success).value)
        repeat(2) { assertNotNull((f.prepare() as AppResult.Success).value) }
        assertEquals(AppResult.Success(null), f.prepare())
        assertEquals(3, f.state.pending.single().preparations)
        assertEquals(AppResult.Success(Unit), f.present(first, f.copy))
        assertTrue(f.shown.isEmpty())
        f.state =
            f.state.copy(
                pending =
                    listOf(f.state.pending.single().copy(preparations = 0, companyId = f.accountId))
            )
        assertEquals(AppResult.Success(null), f.prepare())
        assertEquals(AppResult.Success(f.workspace), f.inbox.workspaceState.value)
        assertEquals(0, f.state.pending.single().preparations)
    }
}
