@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class LoadUnreadInboxTest {
    @Test
    fun countUsesTheRemoteTotalWithoutLoadingOrDecryptingInboxPages() = runTest {
        val f = InboxFixture()
        assertEquals(AppResult.Success(36L), f.unread(f.workspace.id))
        assertEquals(1, f.unreadCalls)
        assertEquals(0, f.itemCalls)
        assertEquals(0, f.bootstrapCalls)
        assertTrue(f.commits.isEmpty())
        val failure = AppResult.Failed(Failure(FailureKind.Network, "fixture", code = "offline"))
        f.unreadCall = { failure }
        assertEquals(failure, f.unread(f.workspace.id))
    }

    @Test
    fun missingOrExpiredAdmissionDoesNotReadTheServer() = runTest {
        var now = Instant.parse("2026-10-11T00:00:00Z")
        val f = InboxFixture(now = { now })
        assertIs<AppResult.Failed>(f.unread("another-workspace"))
        f.workspaceState.value = AppResult.Success(f.workspace.copy(permissions = emptySet()))
        assertIs<AppResult.Failed>(f.unread(f.workspace.id))
        f.workspaceState.value = AppResult.Success(f.workspace)
        now += 2.hours
        assertIs<AppResult.Failed>(f.unread(f.workspace.id))
        f.sessionState.value = AppResult.Success(null)
        assertIs<AppResult.Failed>(f.unread(f.workspace.id))
        assertEquals(0, f.unreadCalls)
    }

    @Test
    fun replacedScopeRejectsLateCountsAndLateNetworkFailures() = runTest {
        for (response in
            listOf(
                AppResult.Success(99L),
                AppResult.Failed(Failure(FailureKind.Network, "fixture")),
            )) {
            val f = InboxFixture()
            val release = CompletableDeferred<Unit>()
            f.unreadCall = {
                release.await()
                response
            }
            val request = async { f.unread(f.workspace.id) }
            runCurrent()
            assertEquals(1, f.unreadCalls)
            f.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
            release.complete(Unit)
            val failure = assertIs<AppResult.Failed>(request.await()).failure
            assertNotEquals(FailureKind.Network, failure.kind)
        }
    }

    @Test
    fun permissionChangesAndCancellationDuringPendingWorkCannotPublishACount() = runTest {
        val f = InboxFixture()
        var release = CompletableDeferred<Unit>()
        f.unreadCall = {
            withContext(NonCancellable) { release.await() }
            AppResult.Success(36L)
        }
        val request = async { f.unread(f.workspace.id) }
        runCurrent()
        f.workspaceState.value =
            AppResult.Success(
                f.workspace.copy(permissions = f.workspace.permissions + "another.permission")
            )
        release.complete(Unit)
        assertEquals(
            "client.inbox_scope_changed",
            assertIs<AppResult.Failed>(request.await()).failure.code,
        )
        f.workspaceState.value = AppResult.Success(f.workspace)
        release = CompletableDeferred()
        var published = false
        val cancelled = launch {
            f.unread(f.workspace.id)
            published = true
        }
        runCurrent()
        cancelled.cancel()
        release.complete(Unit)
        cancelled.join()
        assertFalse(published)
    }
}
