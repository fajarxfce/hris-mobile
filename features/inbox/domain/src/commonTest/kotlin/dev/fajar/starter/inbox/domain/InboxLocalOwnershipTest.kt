@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class InboxLocalOwnershipTest {
    @Test
    fun replacementLoginRetainsLocalFactsAndStagesItsOwnCursorWithoutClearing() = runTest {
        val f = InboxFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val published = f.snapshot.value
        val original = requireNotNull(f.checkpoint)
        val session = f.session.copy(id = "replacement-login")
        f.sessionState.value = AppResult.Success(session)
        f.workspaceState.value = AppResult.Success(f.workspace.copy(sessionId = session.id))
        val access = f.access.copy(sessionId = session.id)
        assertIs<AppResult.Success<*>>(validateStoredInbox(access, original))
        assertIs<AppResult.Failed>(validateInboxActionSnapshot(access, original, f.clock.now()))
        assertEquals(published, f.observe(f.workspace.id, InboxCacheQuery.Page()).first())
        val hydration = CompletableDeferred<AppResult<InboxItem>>()
        f.itemCall = { hydration.await() }
        f.bootstrapCall = { cursor ->
            assertNull(cursor)
            AppResult.Success(
                SyncBootstrapPage(
                    setOf(SyncCollection.INBOX),
                    listOf(f.reference),
                    null,
                    "new-login-cursor",
                    f.clock.now(),
                )
            )
        }
        val work = async { f.sync(f.workspace.id) }
        runCurrent()
        assertFalse(work.isCompleted)
        assertEquals(session.id, f.checkpoint!!.access.sessionId)
        assertNotEquals(original.generation, f.checkpoint!!.generation)
        assertEquals(0, f.clearCalls)
        assertEquals(published, f.observe(f.workspace.id, InboxCacheQuery.Page()).first())
        hydration.complete(AppResult.Failed(Failure(FailureKind.Network, "Fixture")))
        assertIs<AppResult.Failed>(work.await())
        assertEquals(published, f.snapshot.value)
        val generation = f.checkpoint!!.generation
        f.itemCall = { AppResult.Success(f.item.copy(version = 2)) }
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        assertEquals(generation, f.checkpoint!!.generation)
        assertEquals(
            2,
            assertIs<AppResult.Success<InboxCachePage>>(f.snapshot.value)
                .value
                .items
                .single()
                .version,
        )
        assertEquals(0, f.clearCalls)
    }

    @Test
    fun unrelatedPermissionChangesRetainOwnedFactsButRequireAFreshActionReviewAndCursor() =
        runTest {
            val f = InboxFixture()
            assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
            val original = f.snapshot.value
            f.workspaceState.value =
                AppResult.Success(
                    f.workspace.copy(permissions = f.workspace.permissions + "announcements.manage")
                )
            assertEquals(original, f.observe(f.workspace.id, InboxCacheQuery.Page()).first())
            val checkpoint = f.checkpoint!!
            assertIs<AppResult.Failed>(
                validateInboxActionSnapshot(
                    f.access.copy(permissions = f.access.permissions + "announcements.manage"),
                    checkpoint,
                    f.clock.now(),
                )
            )
            f.itemCall = { AppResult.Failed(Failure(FailureKind.Network, "Fixture")) }
            assertEquals(
                FailureKind.Network,
                assertIs<AppResult.Failed>(f.sync(f.workspace.id)).failure.kind,
            )
            assertEquals(original, f.snapshot.value)
            assertEquals(original, f.observe(f.workspace.id, InboxCacheQuery.Page()).first())
            assertEquals(2, f.bootstrapCalls)
            assertNotEquals(checkpoint.generation, f.checkpoint!!.generation)
            assertFalse(f.checkpoint!!.published)
            assertEquals(0, f.clearCalls)
        }

    @Test
    fun lateHydrationFromTheOldLoginCannotPublishIntoTheRetainedAccount() = runTest {
        val f = InboxFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val published = f.snapshot.value
        val original = f.checkpoint
        val response = CompletableDeferred<AppResult<InboxItem>>()
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    listOf(SyncChange(f.reference, SyncOperation.UPSERT)),
                    "next",
                    false,
                    false,
                    5,
                    f.clock.now(),
                )
            )
        }
        f.itemCall = { response.await() }
        val work = async { f.sync(f.workspace.id) }
        runCurrent()
        val session = f.session.copy(id = "replacement-login")
        f.sessionState.value = AppResult.Success(session)
        f.workspaceState.value = AppResult.Success(f.workspace.copy(sessionId = session.id))
        response.complete(AppResult.Success(f.item.copy(version = 2)))
        assertEquals(
            "client.inbox_scope_changed",
            assertIs<AppResult.Failed>(work.await()).failure.code,
        )
        assertEquals(original, f.checkpoint)
        assertEquals(published, f.snapshot.value)
        assertEquals(0, f.clearCalls)
    }
}
