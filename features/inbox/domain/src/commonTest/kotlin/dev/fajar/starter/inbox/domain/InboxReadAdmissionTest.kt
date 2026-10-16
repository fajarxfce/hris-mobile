@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class InboxReadAdmissionTest {
    private val denial = Failure(FailureKind.AccessDenied, "Fixture denial", code = "mfa_required")

    @Test
    fun anotherReaderCannotReopenRetainedFactsAfterFailedCleanup() = runTest {
        val f = InboxFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val saved = f.snapshot.value
        f.clearCall = { AppResult.Failed(Failure(FailureKind.Storage, "Fixture")) }
        f.changesCall = { AppResult.Failed(denial) }
        assertEquals(denial, assertIs<AppResult.Failed>(f.sync(f.workspace.id)).failure)
        assertEquals(saved, f.snapshot.value)
        assertEquals(
            denial.code,
            assertIs<AppResult.Failed>(f.observe(f.workspace.id, InboxCacheQuery.Page()).first())
                .failure
                .code,
        )
    }

    @Test
    fun anUnreadCountStartedBeforeDenialCannotPublishAfterIt() = runTest {
        val f = InboxFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val response = CompletableDeferred<AppResult<Long>>()
        f.unreadCall = { withContext(NonCancellable) { response.await() } }
        val count = async { f.unread(f.workspace.id) }
        try {
            runCurrent()
            assertFalse(count.isCompleted)
            f.clearCall = { AppResult.Failed(Failure(FailureKind.Storage, "Fixture")) }
            f.changesCall = { AppResult.Failed(denial) }
            assertIs<AppResult.Failed>(f.sync(f.workspace.id))
            response.complete(AppResult.Success(36))
            assertEquals(denial.code, assertIs<AppResult.Failed>(count.await()).failure.code)
        } finally {
            response.complete(AppResult.Success(36))
            count.cancel()
        }
    }

    @Test
    fun failedPersistenceAndPendingOrFailedRecoveryKeepEveryReaderDenied() = runTest {
        val f = InboxFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val saved = f.snapshot.value
        val storage = AppResult.Failed(Failure(FailureKind.Storage, "Fixture"))
        f.clearCall = { storage }
        f.restrictionCall = { storage }
        f.changesCall = { AppResult.Failed(denial) }
        assertEquals(denial, assertIs<AppResult.Failed>(f.sync(f.workspace.id)).failure)
        assertEquals(saved, f.snapshot.value)
        val response = CompletableDeferred<AppResult<SyncChangePage>>()
        f.changesCall = { response.await() }
        val recovery = async { f.sync(f.workspace.id) }
        try {
            runCurrent()
            assertFalse(recovery.isCompleted)
            assertEquals(
                denial,
                assertIs<AppResult.Failed>(
                        f.observe(f.workspace.id, InboxCacheQuery.Page()).first()
                    )
                    .failure,
            )
            assertEquals(denial, assertIs<AppResult.Failed>(f.unread(f.workspace.id)).failure)
            assertEquals(0, f.unreadCalls)
            response.complete(AppResult.Failed(Failure(FailureKind.Network, "Fixture")))
            assertIs<AppResult.Failed>(recovery.await())
            assertEquals(
                denial,
                assertIs<AppResult.Failed>(
                        f.observe(f.workspace.id, InboxCacheQuery.Item(f.item.id)).first()
                    )
                    .failure,
            )
            f.changesCall = {
                AppResult.Success(
                    SyncChangePage(emptyList(), "recovered", false, false, 5, f.clock.now())
                )
            }
            f.admitCall = { storage }
            assertEquals(storage, f.sync(f.workspace.id))
            assertEquals(denial, assertIs<AppResult.Failed>(f.unread(f.workspace.id)).failure)
            f.admitCall = { null }
            assertTrue(
                assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id)).value.caughtUp
            )
            assertEquals(
                listOf(f.item),
                assertIs<AppResult.Success<InboxCachePage>>(
                        f.observe(f.workspace.id, InboxCacheQuery.Page()).first()
                    )
                    .value
                    .items,
            )
        } finally {
            response.complete(AppResult.Failed(Failure(FailureKind.Network, "Fixture")))
            recovery.cancel()
        }
    }

    @Test
    fun acquisitionStartedBeforeANewerDenialCannotReopenItsReaders() = runTest {
        val f = InboxFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        f.cache.restrict(f.access, denial)
        val response = CompletableDeferred<AppResult<SyncChangePage>>()
        f.changesCall = { response.await() }
        val recovery = async { f.sync(f.workspace.id) }
        try {
            runCurrent()
            assertFalse(recovery.isCompleted)
            val newer = denial.copy(code = "company_access_denied")
            f.cache.restrict(f.access, newer)
            response.complete(
                AppResult.Success(
                    SyncChangePage(emptyList(), "caught-up", false, false, 5, f.clock.now())
                )
            )
            assertEquals(newer, assertIs<AppResult.Failed>(recovery.await()).failure)
            assertEquals(
                newer,
                assertIs<AppResult.Failed>(
                        f.observe(f.workspace.id, InboxCacheQuery.Page()).first()
                    )
                    .failure,
            )
            assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
            assertIs<AppResult.Success<*>>(
                f.observe(f.workspace.id, InboxCacheQuery.Page()).first()
            )
        } finally {
            response.complete(AppResult.Failed(Failure(FailureKind.Network, "Fixture")))
            recovery.cancel()
        }
    }

    @Test
    fun partialAcquisitionDoesNotReopenAKnownDenial() = runTest {
        val f = InboxFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        f.cache.restrict(f.access, denial)
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(emptyList(), "more-${f.changesCalls}", true, false, 5, f.clock.now())
            )
        }
        assertFalse(
            assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id)).value.caughtUp
        )
        assertEquals(11, f.changesCalls)
        assertEquals(
            denial,
            assertIs<AppResult.Failed>(f.observe(f.workspace.id, InboxCacheQuery.Page()).first())
                .failure,
        )
    }

    @Test
    fun storedCommandsStayImmutableWhileObservationPreparationAndReplayAreDenied() = runTest {
        val f = InboxCommandFixture()
        val record = assertIs<AppResult.Success<InboxCommandRecord>>(f.queue()).value
        f.inbox.entries[f.inbox.item.id] =
            InboxReplicaEntry(f.inbox.item.id, f.inbox.item.version, f.inbox.item)
        f.inbox.cache.restrict(f.inbox.access, denial)
        assertEquals(
            denial,
            assertIs<AppResult.Failed>(
                    f.observe(f.inbox.workspace.id, record.command.itemId).first()
                )
                .failure,
        )
        assertEquals(denial, assertIs<AppResult.Failed>(f.queue()).failure)
        assertEquals(
            denial,
            assertIs<AppResult.Failed>(f.replay(f.inbox.workspace.id, record.command.id)).failure,
        )
        assertEquals(
            denial,
            assertIs<AppResult.Failed>(f.review(f.inbox.workspace.id, record.command.id)).failure,
        )
        assertEquals(listOf(record), f.records.value)
        assertTrue(f.transmitted.isEmpty())
        assertEquals(0, f.itemCalls)
        assertIs<AppResult.Success<*>>(f.inbox.sync(f.inbox.workspace.id))
        assertIs<AppResult.Success<*>>(f.replay(f.inbox.workspace.id, record.command.id))
        assertEquals(listOf(record.command), f.transmitted)
    }

    @Test
    fun commandDenialClosesReadersEvenWhenItsOutcomeCannotBeSaved() = runTest {
        val f = InboxCommandFixture()
        val record = assertIs<AppResult.Success<InboxCommandRecord>>(f.queue()).value
        f.inbox.clearCall = { AppResult.Failed(Failure(FailureKind.Storage, "Fixture")) }
        f.transmit = {
            f.updateFailure = Failure(FailureKind.Storage, "Fixture")
            AppResult.Failed(denial)
        }
        assertEquals(
            denial,
            assertIs<AppResult.Failed>(f.replay(f.inbox.workspace.id, record.command.id)).failure,
        )
        assertEquals(
            denial,
            assertIs<AppResult.Failed>(
                    f.inbox.observe(f.inbox.workspace.id, InboxCacheQuery.Page()).first()
                )
                .failure,
        )
        val retained = f.records.value.single()
        assertEquals(record.command, retained.command)
        assertIs<InboxCommandOutcome.Unconfirmed>(retained.outcome)
        assertEquals(1, retained.attempts)
    }

    @Test
    fun aConcurrentHydrationDenialTakesPrecedenceOverATransportFailure() = runTest {
        val f = InboxFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        f.clearCall = { AppResult.Failed(Failure(FailureKind.Storage, "Fixture")) }
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    listOf(
                        SyncChange(f.reference, SyncOperation.UPSERT),
                        SyncChange(f.reference.copy(id = "another"), SyncOperation.UPSERT),
                    ),
                    "next",
                    false,
                    false,
                    5,
                    f.clock.now(),
                )
            )
        }
        f.itemCall = { id ->
            if (id == f.item.id) AppResult.Failed(Failure(FailureKind.Network, "Fixture"))
            else AppResult.Failed(denial)
        }
        assertEquals(denial, assertIs<AppResult.Failed>(f.sync(f.workspace.id)).failure)
        assertEquals(denial, assertIs<AppResult.Failed>(f.unread(f.workspace.id)).failure)
    }

    @Test
    fun cancellationDuringAdmissionCannotStartAnotherAcquisition() = runTest {
        val f = InboxFixture()
        val response = CompletableDeferred<AppResult<InboxReadAdmission>>()
        f.admissionCall = { withContext(NonCancellable) { response.await() } }
        val work = launch { f.sync(f.workspace.id) }
        try {
            runCurrent()
            work.cancel()
            response.complete(AppResult.Success(InboxReadAdmission(null, null)))
            work.join()
            assertTrue(work.isCancelled)
            assertEquals(0, f.policyCalls)
            assertEquals(0, f.bootstrapCalls)
        } finally {
            response.complete(AppResult.Success(InboxReadAdmission(null, null)))
            work.cancel()
        }
    }

    @Test
    fun aLateCountDenialCannotRestrictAReplacementWorkspace() = runTest {
        val f = InboxFixture()
        val response = CompletableDeferred<AppResult<Long>>()
        f.unreadCall = { response.await() }
        val count = async { f.unread(f.workspace.id) }
        try {
            runCurrent()
            f.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
            response.complete(AppResult.Failed(denial))
            assertEquals(
                "client.workspace_changed",
                assertIs<AppResult.Failed>(count.await()).failure.code,
            )
            assertEquals(0, f.restrictionWrites)
        } finally {
            response.complete(AppResult.Failed(denial))
            count.cancel()
        }
    }
}
