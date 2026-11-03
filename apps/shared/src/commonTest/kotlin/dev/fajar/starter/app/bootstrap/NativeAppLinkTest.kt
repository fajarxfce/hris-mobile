@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.app.bootstrap

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.app.navigation.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationNotificationInvocation
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.AccountRepository
import dev.fajar.starter.notifications.data.dto.ActionNotificationInput
import dev.fajar.starter.workspace.domain.usecases.SelectWorkspace
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class NativeAppLinkTest {
    @Test
    fun choosingACompanyJoinsPendingNativeCleanupBeforeLeavingTheCurrentWorkspace() = runTest {
        val f = Fixture(this)
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            f.open = {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanup.complete(Unit)
                        release.await()
                    }
                }
            }
            runCurrent()
            f.vm.onEvent(AppEvent.NativeLinkReceived(notificationInput()))
            runCurrent()
            f.vm.onEvent(AppEvent.CompanyChangeRequested)
            runCurrent()
            assertTrue(cleanup.isCompleted)
            assertEquals(AppResult.Success(f.selected), f.workspaces.current(testSession().id))
            assertTrue(f.vm.state.value.changingWorkspace)
            release.complete(Unit)
            runCurrent()
            assertEquals(AppResult.Success(null), f.workspaces.current(testSession().id))
            assertNull(f.vm.state.value.pendingLink)
            assertFalse(f.vm.state.value.openingNotification)
            assertFalse(f.vm.state.value.changingWorkspace)
            assertEquals(0, f.selections)
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    @Test
    fun nativeInputWaitsForBootstrapAndThenOpensItsOwnedWorkspace() = runTest {
        val f = Fixture(this, signedIn = false)
        try {
            f.vm.onEvent(AppEvent.NativeLinkReceived(notificationInput()))
            runCurrent()
            assertEquals(0, f.opens)
            assertNull(f.vm.state.value.pendingLink)
            f.sessions.value.value = AppResult.Success(testSession())
            runCurrent()
            assertEquals(1, f.opens)
            assertEquals(0, f.selections)
            assertEquals(f.selected.id, f.vm.state.value.pendingLink?.workspaceId)
            assertFalse(f.vm.state.value.openingNotification)
        } finally {
            f.close()
        }
    }

    @Test
    fun nativeFamilyReplacementRejectsAnUncancellableLateAuthentication() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            f.open = {
                withContext(NonCancellable) { release.await() }
                AppResult.Success(notificationInvocation(company = "other"))
            }
            runCurrent()
            f.vm.onEvent(AppEvent.NativeLinkReceived(notificationInput()))
            runCurrent()
            assertTrue(f.vm.state.value.openingNotification)
            f.sessions.value.value =
                AppResult.Success(
                    testSession()
                        .copy(binding = testSession().binding.copy(serverId = "replacement"))
                )
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertNull(f.vm.state.value.pendingLink)
            assertNull(f.vm.state.value.notificationOpenFailure)
            assertFalse(f.vm.state.value.openingNotification)
            assertEquals(0, f.selections)
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    @Test
    fun newerNativeRequestWaitsForThePreviousRequestsCleanup() = runTest {
        val f = Fixture(this)
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var joined = false
        try {
            f.open = { input ->
                if (input.envelope == "first") {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanup.complete(Unit)
                            release.await()
                            joined = true
                        }
                    }
                } else {
                    assertTrue(joined)
                    AppResult.Success(notificationInvocation(conversation = input.envelope))
                }
            }
            runCurrent()
            f.vm.onEvent(AppEvent.NativeLinkReceived(notificationInput("first")))
            runCurrent()
            f.vm.onEvent(AppEvent.NativeLinkReceived(notificationInput("second")))
            runCurrent()
            assertTrue(cleanup.isCompleted)
            assertEquals(1, f.opens)
            assertNull(f.vm.state.value.pendingLink)
            release.complete(Unit)
            runCurrent()
            assertEquals(2, f.opens)
            val link = assertNotNull(f.vm.state.value.pendingLink)
            assertEquals("second", assertIs<AppLink.ChatThread>(link.target).conversationId)
            f.vm.onEvent(AppEvent.LinkHandled(link))
            runCurrent()
            assertNull(f.vm.state.value.pendingLink)
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    @Test
    fun newerPublicLinkAlsoWaitsForNativeCleanupAndWins() = runTest {
        val f = Fixture(this)
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            f.open = {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanup.complete(Unit)
                        release.await()
                    }
                }
            }
            runCurrent()
            f.vm.onEvent(AppEvent.NativeLinkReceived(notificationInput()))
            runCurrent()
            f.vm.onEvent(AppEvent.LinkReceived("hrismobile-dev://app/inbox"))
            runCurrent()
            assertTrue(cleanup.isCompleted)
            assertNull(f.vm.state.value.pendingLink)
            release.complete(Unit)
            runCurrent()
            assertEquals(AppLink.Inbox, f.vm.state.value.pendingLink?.target)
            assertEquals(0, f.selections)
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    @Test
    fun aManualCompanyChoiceBeforeAuthenticationFinishesCannotBeOverwritten() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            f.open = {
                release.await()
                AppResult.Success(notificationInvocation(company = "other"))
            }
            runCurrent()
            f.vm.onEvent(AppEvent.NativeLinkReceived(notificationInput()))
            runCurrent()
            val manual =
                f.selected.copy(
                    id = "manual",
                    company = f.selected.company.copy(id = "manual-company"),
                )
            assertEquals(
                AppResult.Success(true),
                f.workspaces.compareAndSet(testSession().id, f.selected, manual),
            )
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertNull(f.vm.state.value.pendingLink)
            assertEquals(FailureKind.Conflict, f.vm.state.value.notificationOpenFailure?.kind)
            assertEquals(AppResult.Success(manual), f.workspaces.current(testSession().id))
            assertEquals(0, f.accountLoads)
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    @Test
    fun mfaFailureCanUseNormalCompanySelectionAndExplicitRetry() = runTest {
        val f = Fixture(this)
        try {
            f.mfaRequired = true
            f.open = { AppResult.Success(notificationInvocation(company = "other")) }
            runCurrent()
            f.vm.onEvent(AppEvent.NativeLinkReceived(notificationInput()))
            runCurrent()
            assertEquals("mfa_required", f.vm.state.value.notificationOpenFailure?.code)
            assertNull(f.vm.state.value.pendingLink)
            assertEquals(AppResult.Success(f.selected), f.workspaces.current(testSession().id))
            f.vm.onEvent(AppEvent.CompanyChangeRequested)
            runCurrent()
            assertNull((f.vm.state.value.stage as AppStage.SignedIn).workspace)
            f.mfaRequired = false
            f.vm.onEvent(AppEvent.NativeLinkRetry)
            runCurrent()
            val link = assertNotNull(f.vm.state.value.pendingLink)
            assertEquals("other", assertIs<AppLink.ChatThread>(link.target).companyId)
            assertEquals(
                link.workspaceId,
                (f.vm.state.value.stage as AppStage.SignedIn).workspace?.id,
            )
            assertEquals(2, f.selections)
            assertNull(f.vm.state.value.notificationOpenFailure)
        } finally {
            f.close()
        }
    }

    @Test
    fun aTimeoutNeedsExplicitRetryAndUnrelatedStateEmissionsDoNotRepeatIt() = runTest {
        val f = Fixture(this)
        try {
            f.open = { awaitCancellation() }
            runCurrent()
            f.vm.onEvent(AppEvent.NativeLinkReceived(notificationInput()))
            runCurrent()
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(FailureKind.Timeout, f.vm.state.value.notificationOpenFailure?.kind)
            assertFalse(f.vm.state.value.openingNotification)
            advanceTimeBy(70_000)
            runCurrent()
            assertEquals(1, f.opens)
            f.open = { AppResult.Success(notificationInvocation()) }
            f.vm.onEvent(AppEvent.NativeLinkRetry)
            runCurrent()
            assertEquals(2, f.opens)
            assertNotNull(f.vm.state.value.pendingLink)
        } finally {
            f.close()
        }
    }

    @Test
    fun disposingTheViewModelCancelsAuthenticationWithoutPublishingOrSelecting() = runTest {
        val f = Fixture(this)
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            f.open = {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanup.complete(Unit)
                        release.await()
                    }
                }
            }
            runCurrent()
            f.vm.onEvent(AppEvent.NativeLinkReceived(notificationInput()))
            runCurrent()
            f.store.clear()
            runCurrent()
            assertTrue(cleanup.isCompleted)
            release.complete(Unit)
            runCurrent()
            assertNull(f.vm.state.value.pendingLink)
            assertEquals(0, f.selections)
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    private class Fixture(private val scope: TestScope, signedIn: Boolean = true) {
        init {
            Dispatchers.setMain(StandardTestDispatcher(scope.testScheduler))
        }

        val store = ViewModelStore()
        val sessions = TestSessions(if (signedIn) testSession() else null)
        val selected = testWorkspace()
        val workspaces = TestWorkspaces(selected)
        var opens = 0
        var selections = 0
        var accountLoads = 0
        var mfaRequired = false
        var open:
            suspend (ActionNotificationInput) -> AppResult<CollaborationNotificationInvocation> =
            {
                AppResult.Success(notificationInvocation())
            }
        private val accounts =
            object : AccountRepository {
                override suspend fun load(sessionId: String): AppResult<AccountSnapshot> {
                    accountLoads++
                    return AppResult.Success(
                        AccountSnapshot(
                            testSession().user,
                            listOf(selected.company, selected.company.copy(id = "other")),
                            emptySet(),
                            SessionAssurance(mfaRequired, false, false, null, null),
                            false,
                        )
                    )
                }
            }
        private val select = SelectWorkspace(sessions, accounts, workspaces)
        val vm =
            bootstrapViewModel(
                sessions,
                workspaces,
                accounts,
                nativeNavigation =
                    NativeNotificationNavigation(
                        open = {
                            opens++
                            open(it)
                        },
                        select = { session, company, origin ->
                            selections++
                            select(session, company, origin)
                        },
                    ),
            )

        init {
            store.put("app", vm)
        }

        fun close() {
            store.clear()
            scope.runCurrent()
            Dispatchers.resetMain()
        }
    }
}
