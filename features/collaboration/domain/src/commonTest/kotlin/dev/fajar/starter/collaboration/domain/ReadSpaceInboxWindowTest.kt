package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadSpaceInboxWindowTest {
    @Test
    fun ownedWindowsIgnoreCredentialAgeAndCompanySelectionWithoutAcquisition() = runTest {
        val f = SpaceInboxWindowFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        f.base.native.setup.beforeWorkspace = { error("No selected company") }
        f.base.native.onPage = { error("No acquisition") }
        assertEquals(AppResult.Success(f.value), f.read(f.access))
        assertEquals<List<Pair<CollaborationSpaceInboxWindowAnchor, Int>>>(
            listOf(CollaborationSpaceInboxWindowAnchor.Latest to 50),
            f.requests,
        )
        assertEquals(0, f.base.native.pageCalls)
    }

    @Test
    fun invalidRequestsAndConfirmedDenialDoNotReadLocalWindows() = runTest {
        val f = SpaceInboxWindowFixture()
        for (limit in listOf(0, 51)) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.read(f.access, limit = limit)).failure.kind,
        )
        assertIs<AppResult.Failed>(
            f.read(f.access, CollaborationSpaceInboxWindowAnchor.Conversation("invalid"))
        )
        assertIs<AppResult.Failed>(f.read(f.access, CollaborationSpaceInboxWindowAnchor.Newer("")))
        f.base.base.deny()
        assertEquals(AppResult.Failed(f.base.base.denied), f.read(f.access))
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun missingRefreshAnchorFallsBackOnceButChangedCursorsNeverRetry() = runTest {
        val f = SpaceInboxWindowFixture()
        val anchor = CollaborationSpaceInboxWindowAnchor.Conversation(f.page.id)
        f.onRead = {
            if (it == anchor) AppResult.Success(CollaborationSpaceInboxWindow.MissingAnchor)
            else AppResult.Success(f.value)
        }
        assertEquals(AppResult.Success(f.value), f.read(f.access, anchor, 7))
        assertEquals<List<Pair<CollaborationSpaceInboxWindowAnchor, Int>>>(
            listOf(anchor to 7, CollaborationSpaceInboxWindowAnchor.Latest to 7),
            f.requests,
        )
        f.requests.clear()
        f.onRead = { AppResult.Success(CollaborationSpaceInboxWindow.Changed) }
        val older = CollaborationSpaceInboxWindowAnchor.Older("captured")
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxWindow.Changed),
            f.read(f.access, older),
        )
        assertEquals(1, f.requests.size)
    }

    @Test
    fun failedOrMalformedFallbackTerminatesWithoutAnotherRead() = runTest {
        for (failed in listOf(false, true)) {
            val f = SpaceInboxWindowFixture()
            f.onRead = {
                if (f.requests.size == 1)
                    AppResult.Success(CollaborationSpaceInboxWindow.MissingAnchor)
                else if (failed) AppResult.Failed(f.page.failure)
                else AppResult.Success(CollaborationSpaceInboxWindow.MissingAnchor)
            }
            val result =
                assertIs<AppResult.Failed>(
                    f.read(f.access, CollaborationSpaceInboxWindowAnchor.Conversation(f.page.id))
                )
            assertEquals(
                if (failed) f.page.failure.kind else FailureKind.Unexpected,
                result.failure.kind,
            )
            assertEquals(2, f.requests.size)
        }
    }

    @Test
    fun denialDuringMissingAnchorReadPreventsTheFallback() = runTest {
        val f = SpaceInboxWindowFixture()
        f.onRead = {
            f.base.base.deny()
            AppResult.Success(CollaborationSpaceInboxWindow.MissingAnchor)
        }
        assertEquals(
            AppResult.Failed(f.base.base.denied),
            f.read(f.access, CollaborationSpaceInboxWindowAnchor.Conversation(f.page.id)),
        )
        assertEquals(1, f.requests.size)
    }

    @Test
    fun accountReplacementDuringReadRejectsRetainedRows() = runTest {
        for (kind in 0..3) {
            val f = SpaceInboxWindowFixture()
            f.onRead = {
                val session = f.base.native.session
                f.base.identity.value =
                    AppResult.Success(
                        when (kind) {
                            0 -> null
                            1 -> session.copy(id = "replacement")
                            2 -> session.copy(user = session.user.copy(id = f.page.other))
                            else ->
                                session.copy(
                                    binding =
                                        session.binding.copy(
                                            audience = "https://other.example.test/"
                                        )
                                )
                        }
                    )
                AppResult.Success(f.value)
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.read(f.access)).failure.kind,
            )
            assertEquals(1, f.requests.size)
        }
    }

    @Test
    fun identityReplacementDuringAdmissionStopsBeforeLocalRead() = runTest {
        val f = SpaceInboxWindowFixture()
        f.base.currentAdmission = {
            f.base.identity.value = AppResult.Success(null)
            AppResult.Success(f.base.base.open)
        }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.read(f.access)).failure.kind,
        )
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun repositoryListsAreDetachedBeforeAdmissionSuspends() = runTest {
        val f = SpaceInboxWindowFixture()
        val rows = mutableListOf(f.page.entry())
        var returned = false
        f.onRead = {
            returned = true
            AppResult.Success(f.value.copy(entries = rows))
        }
        f.base.currentAdmission = {
            if (returned) rows.clear()
            AppResult.Success(f.base.base.open)
        }
        assertEquals(AppResult.Success(f.value), f.read(f.access))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun malformedOrOversizedRowsRemainFailuresAndDoNotRetry() = runTest {
        val f = SpaceInboxWindowFixture()
        for (value in
            listOf(
                f.value.copy(entries = List(51) { f.page.entry() }),
                f.value.copy(version = null),
                f.value.copy(previous = "unexpected"),
                f.value.copy(entries = listOf(f.page.entry(), f.page.entry())),
            )) {
            f.onRead = { AppResult.Success(value) }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.read(f.access)).failure.kind,
            )
        }
        assertEquals(4, f.requests.size)
    }

    @Test
    fun emptyTerminalAndStorageFailureNeverManufactureContinuation() = runTest {
        val f = SpaceInboxWindowFixture()
        val terminal = f.value.copy(entries = emptyList())
        f.onRead = { AppResult.Success(terminal) }
        assertEquals(
            AppResult.Success(terminal),
            f.read(f.access, CollaborationSpaceInboxWindowAnchor.Older("older")),
        )
        f.onRead = { AppResult.Failed(f.page.failure) }
        assertEquals(AppResult.Failed(f.page.failure), f.read(f.access))
        assertEquals(2, f.requests.size)
    }

    @Test
    fun cancellationRejectsAnUncooperativeLateReadAndNeverFallsBack() = runTest {
        val f = SpaceInboxWindowFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var returned = false
        f.onRead = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                AppResult.Success(CollaborationSpaceInboxWindow.MissingAnchor)
            }
        }
        val job = async {
            f.read(f.access, CollaborationSpaceInboxWindowAnchor.Conversation(f.page.id))
            returned = true
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        assertFalse(returned)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun denialWinsOverSuccessfulAndFailedLocalResults() = runTest {
        for (failed in listOf(false, true)) {
            val f = SpaceInboxWindowFixture()
            f.onRead = {
                f.base.base.deny()
                if (failed) AppResult.Failed(f.page.failure) else AppResult.Success(f.value)
            }
            assertEquals(AppResult.Failed(f.base.base.denied), f.read(f.access))
            assertEquals(1, f.requests.size)
        }
    }

    @Test
    fun cancellationWhileFinalAdmissionWaitsCannotPublishCapturedRows() = runTest {
        val f = SpaceInboxWindowFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var read = false
        var returned = false
        f.onRead = {
            read = true
            AppResult.Success(f.value)
        }
        f.base.currentAdmission = {
            if (read)
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            AppResult.Success(f.base.base.open)
        }
        val job = async {
            f.read(f.access)
            returned = true
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        assertFalse(returned)
        assertEquals(1, f.requests.size)
    }
}
