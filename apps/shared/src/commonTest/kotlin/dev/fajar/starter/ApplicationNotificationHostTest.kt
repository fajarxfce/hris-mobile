@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.datasources.ActionNotificationDisplaySource
import dev.fajar.starter.notifications.data.dto.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

/** Actual process/surface lifetime with virtual time and an explicitly fake native display. */
class ApplicationNotificationHostTest {
    @Test
    fun startsOnlyOnceAndCompanyDenialKeepsTheActualNativeParent() = runTest {
        val f = Fixture(this)
        try {
            f.host.start()
            f.host.start()
            runCurrent()
            assertEquals(1, f.created)
            assertEquals(f.parent, f.ready().parent)
            val before = f.clears
            f.states.value = publication(f.parent, denied = true)
            runCurrent()
            assertEquals(f.parent, f.ready().parent)
            assertEquals(before, f.clears)
        } finally {
            f.host.closeAndJoin()
        }
    }

    @Test
    fun replacementCannotBecomeReadyUntilOldNativeCleanupCompletes() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        var entered = false
        try {
            f.host.start()
            runCurrent()
            f.onClear = {
                entered = true
                withContext(NonCancellable) { release.await() }
            }
            val replacement = f.parent.copy(nativeSessionId = "new-family")
            f.states.value = publication(replacement)
            runCurrent()
            assertTrue(entered)
            assertEquals(replacement, f.captured.value)
            assertIs<AppResult.Failed>(f.owner.withOwner(f.parent) { AppResult.Success(Unit) })
            assertIs<AppResult.Failed>(f.owner.withOwner(replacement) { AppResult.Success(Unit) })
            release.complete(Unit)
            runCurrent()
            assertEquals(replacement, f.ready().parent)
        } finally {
            release.complete(Unit)
            f.host.closeAndJoin()
        }
    }

    @Test
    fun closingInvalidatesParentImmediatelyAndJoinsHeldCleanup() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            f.host.start()
            runCurrent()
            f.onClear = { withContext(NonCancellable) { release.await() } }
            val closed = async { f.host.closeAndJoin() }
            runCurrent()
            assertNull(f.captured.value)
            assertFalse(closed.isCompleted)
            f.states.value = publication(f.parent.copy(nativeSessionId = "late-family"))
            runCurrent()
            assertNull(f.captured.value)
            release.complete(Unit)
            closed.await()
            assertIs<AppResult.Failed>(f.delivery.retry())
            f.host.start()
            runCurrent()
            assertEquals(1, f.created)
        } finally {
            release.complete(Unit)
            f.host.closeAndJoin()
        }
    }

    @Test
    fun closeBeforeStartDoesNotCreateAProcess() = runTest {
        val f = Fixture(this)
        f.host.closeAndJoin()
        f.host.start()
        runCurrent()
        assertEquals(0, f.created)
        assertEquals(0, f.clears)
    }

    @Test
    fun sourceCompletionAndFailureBothRetireTheOwnedProcess() = runTest {
        for (failed in listOf(false, true)) {
            val f = Fixture(this)
            val stop = CompletableDeferred<Unit>()
            val source = flow {
                emit(publication(f.parent))
                stop.await()
                if (failed) error("Fictional source failure")
            }
            val host = f.host(source)
            try {
                host.start()
                runCurrent()
                assertEquals(f.parent, f.ready().parent)
                stop.complete(Unit)
                runCurrent()
                assertNull(f.captured.value)
                assertEquals(CollaborationNotificationSurfaceState.Closed, f.owner.states.first())
                assertIs<AppResult.Failed>(f.delivery.retry())
            } finally {
                stop.complete(Unit)
                host.closeAndJoin()
                f.host.closeAndJoin()
            }
        }
    }

    @Test
    fun rejectedSecondHostCannotCloseOrClearTheFirstHostsDelivery() = runTest {
        val first = Fixture(this)
        val second = Fixture(this, first.delivery)
        try {
            first.host.start()
            runCurrent()
            val clears = first.clears
            second.host.start()
            runCurrent()
            second.host.closeAndJoin()
            assertEquals(clears, first.clears)
            assertEquals(first.parent, first.ready().parent)
            assertEquals(0, second.clears)
            assertIs<AppResult.Success<*>>(first.delivery.retry())
        } finally {
            second.host.closeAndJoin()
            first.host.closeAndJoin()
        }
    }

    private class Fixture(
        scope: TestScope,
        val delivery: CollaborationNotificationDelivery = CollaborationNotificationDelivery(),
    ) {
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val parent =
            CollaborationNativeParent("local", "https://example.test/", "account", "family")
        val states = MutableStateFlow<ApplicationCollaborationState>(publication(parent))
        var created = 0
        var clears = 0
        var onClear: suspend () -> Unit = {}
        lateinit var captured: StateFlow<CollaborationNativeParent?>
        lateinit var owner: CollaborationNotificationSurfaceOwner
        private val display =
            object : ActionNotificationDisplaySource {
                override suspend fun show(payload: ActionNotificationPayload) =
                    error("No publication in lifetime-only test")

                override suspend fun active() = emptyList<ActionNotificationReference>()

                override suspend fun remove(expected: ActionNotificationReference) =
                    ActionNotificationRemoval.Absent

                override suspend fun clear() {
                    clears++
                    onClear()
                }
            }
        val host = host(states)

        fun host(source: Flow<ApplicationCollaborationState>) =
            ApplicationNotificationHost(
                source,
                delivery,
                create = { parents ->
                    created++
                    captured = parents
                    owner = CollaborationNotificationSurfaceOwner(parents, display, dispatcher)
                    CollaborationNotificationProcess(
                        owner,
                        read = { p, _ ->
                            AppResult.Success(
                                CollaborationNotificationPublicationReferences(p, emptyList(), null)
                            )
                        },
                        observe = { _, _, _ -> error("No watched references") },
                        reconcile = { _, _, _ -> error("No watched references") },
                        publishCandidate = { _, _, _ -> error("No publication requested") },
                        dispatcher = dispatcher,
                    )
                },
                dispatcher = dispatcher,
            )

        suspend fun ready() =
            assertIs<CollaborationNotificationSurfaceState.Ready>(owner.states.first())
    }
}

private fun publication(parent: CollaborationNativeParent, denied: Boolean = false) =
    ApplicationCollaborationState.Observed(
        AppResult.Success(
            CollaborationRuntimePublication(
                CollaborationRuntimeRetention(parent, null, if (denied) "company" else null),
                if (denied)
                    AppResult.Failed(Failure(FailureKind.AccessDenied, "Company restricted"))
                else AppResult.Success(null),
                AppResult.Success(Unit),
            )
        )
    )
