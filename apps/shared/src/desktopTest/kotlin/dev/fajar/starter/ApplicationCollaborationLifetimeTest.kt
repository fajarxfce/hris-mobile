@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeRepository
import dev.fajar.starter.collaboration.domain.usecases.MaintainCollaborationRuntimeOwnership
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ApplicationCollaborationLifetimeTest {
    @Test
    fun pendingSessionIsUnknownAndParentOnlyPublicationDoesNotScheduleSelectedDelivery() =
        runBlocking {
            withFixture { f ->
                val release = f.gate()
                f.sessionSource = flow {
                    release.await()
                    emitAll(f.session)
                }
                f.beforeWorkspace = { awaitCancellation() }
                assertEquals(ApplicationCollaborationState.Pending, f.lifetime.state.value)
                f.lifetime.start()
                f.lifetime.start()
                f.scheduler.runCurrent()
                assertEquals(ApplicationCollaborationState.Pending, f.lifetime.state.value)
                assertEquals(1, f.sessionObservers)
                assertTrue(f.retentions.isEmpty())
                release.complete(Unit)
                f.scheduler.runCurrent()
                val published = f.published()
                assertEquals(f.app.session.id, published.retention.parent?.sessionId)
                assertNull(published.retention.foreground)
                assertEquals(1, f.workspaceObservers)
                assertTrue(f.scheduled.isEmpty())
            }
        }

    @Test
    fun processedParentRemainsExplicitWhenCleanupFailsWithoutClaimingReadyNativeResources() =
        runBlocking {
            withFixture { f ->
                val failure = AppResult.Failed(Failure(FailureKind.Unavailable, "Cleanup pending."))
                f.onRetention = { failure }
                f.lifetime.start()
                f.scheduler.runCurrent()
                val published = f.published()
                assertEquals(f.app.session.id, published.retention.parent?.sessionId)
                assertNotNull(published.retention.foreground)
                assertIs<AppResult.Success<*>>(published.ownership)
                assertEquals(failure, published.cleanup)
                assertEquals(failure, published.result)
                assertTrue(f.scheduled.isEmpty())
                assertEquals(0, f.app.sdk.backgroundAcquisitions)
            }
        }

    @Test
    fun closeClearsReadinessBeforeLateNoncancellableRetentionCanReturn() = runBlocking {
        withFixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = f.gate()
            var first = true
            f.beforeWorkspace = { awaitCancellation() }
            f.onRetention = {
                if (first) {
                    first = false
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                AppResult.Success(Unit)
            }
            f.lifetime.start()
            f.scheduler.runCurrent()
            assertTrue(entered.isCompleted)
            f.lifetime.close()
            assertEquals(ApplicationCollaborationState.Stopped(), f.lifetime.state.value)
            release.complete(Unit)
            f.scheduler.runCurrent()
            f.lifetime.closeAndJoin()
            assertEquals(ApplicationCollaborationState.Stopped(), f.lifetime.state.value)
            assertNull(f.retentions.last().parent)
            assertTrue(f.scheduled.isEmpty())
            assertEquals(0, f.sessionObservers)
            assertEquals(0, f.workspaceObservers)
            f.lifetime.start()
            f.scheduler.runCurrent()
            assertEquals(0, f.sessionObservers)
        }
    }

    @Test
    fun unexpectedSessionStreamFailureClearsPublishedParentAndDisposesTheSingleObserver() =
        runBlocking {
            withFixture { f ->
                val fail = f.gate()
                f.sessionSource = flow {
                    emit(f.session.value)
                    fail.await()
                    error("Fictional private error must not escape")
                }
                f.lifetime.start()
                f.scheduler.runCurrent()
                assertNotNull(f.published().retention.parent)
                fail.complete(Unit)
                f.scheduler.runCurrent()
                val stopped =
                    assertIs<ApplicationCollaborationState.Stopped>(f.lifetime.state.value)
                assertEquals("client.collaboration_runtime_stopped", stopped.failure?.code)
                assertEquals("Chat connection stopped.", stopped.failure?.message)
                assertNull(f.retentions.last().parent)
                assertEquals(0, f.sessionObservers)
                assertEquals(0, f.workspaceObservers)
            }
        }

    @Test
    fun ordinarySourceCompletionAfterLogoutCannotLeaveAnOldPublicationOrRestartItself() =
        runBlocking {
            withFixture { f ->
                val finish = f.gate()
                f.sessionSource = flow {
                    emit(f.session.value)
                    finish.await()
                    emit(AppResult.Success(null))
                }
                f.lifetime.start()
                f.scheduler.runCurrent()
                assertNotNull(f.published().retention.parent)
                val scheduled = f.scheduled.toList()
                finish.complete(Unit)
                f.scheduler.runCurrent()
                assertIs<ApplicationCollaborationState.Stopped>(f.lifetime.state.value)
                assertNull(f.retentions.last().parent)
                assertEquals(scheduled, f.scheduled)
                assertEquals(0, f.sessionObservers)
                assertEquals(0, f.workspaceObservers)
                f.lifetime.start()
                f.scheduler.runCurrent()
                assertEquals(0, f.sessionObservers)
            }
        }

    @Test
    fun replacementAndLogoutReplacePublishedParentWhileWorkspaceRemainsPending() = runBlocking {
        withFixture { f ->
            f.beforeWorkspace = { awaitCancellation() }
            f.lifetime.start()
            f.scheduler.runCurrent()
            val old = assertNotNull(f.published().retention.parent)
            val next =
                f.app.session.copy(
                    binding =
                        f.app.session.binding.copy(
                            serverId = "a1000000-0000-4000-8000-000000000099"
                        )
                )
            f.session.value = AppResult.Success(next)
            f.scheduler.runCurrent()
            val replacement = assertNotNull(f.published().retention.parent)
            assertNotEquals(old, replacement)
            assertEquals(next.binding.serverId, replacement.nativeSessionId)
            assertEquals(1, f.workspaceObservers)
            f.session.value = AppResult.Success(null)
            f.scheduler.runCurrent()
            assertNull(f.published().retention.parent)
            assertEquals(0, f.workspaceObservers)
            assertTrue(f.scheduled.isEmpty())
        }
    }

    private suspend fun withFixture(block: suspend (LifetimeFixture) -> Unit) {
        val app = CollaborationWorkerFixture()
        var fixture: LifetimeFixture? = null
        try {
            app.initialize()
            val workspace =
                assertIs<AppResult.Success<Workspace?>>(
                        app.koin.get<WorkspaceRepository>().current(app.session.id)
                    )
                    .value!!
            fixture = LifetimeFixture(app, workspace)
            block(fixture)
        } finally {
            try {
                fixture?.close()
            } finally {
                app.close()
            }
        }
    }
}

private class LifetimeFixture(val app: CollaborationWorkerFixture, selected: Workspace) {
    val scheduler = TestCoroutineScheduler()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    fun gate() = CompletableDeferred<Unit>().also(gates::add)

    suspend fun close() {
        lifetime.close()
        gates.forEach { it.complete(Unit) }
        scheduler.runCurrent()
        withTimeout(5_000) { lifetime.closeAndJoin() }
    }

    val session = MutableStateFlow<AppResult<Session?>>(AppResult.Success(app.session))
    var sessionSource: Flow<AppResult<Session?>> = session
    var beforeWorkspace: suspend () -> Unit = {}
    var sessionObservers = 0
    var workspaceObservers = 0
    val retentions = mutableListOf<CollaborationRuntimeRetention>()
    var onRetention: suspend (CollaborationRuntimeRetention) -> AppResult<Unit> = {
        AppResult.Success(Unit)
    }
    val scheduled = mutableListOf<String>()
    private val sessions =
        object : SessionRepository by app.koin.get<SessionRepository>() {
            override fun observe() = flow {
                sessionObservers++
                try {
                    emitAll(sessionSource)
                } finally {
                    sessionObservers--
                }
            }
        }
    private val workspaces =
        object : WorkspaceRepository by app.koin.get<WorkspaceRepository>() {
            override fun observe(sessionId: String): Flow<AppResult<Workspace?>> = flow {
                workspaceObservers++
                try {
                    beforeWorkspace()
                    emit(AppResult.Success(selected))
                    awaitCancellation()
                } finally {
                    workspaceObservers--
                }
            }
        }
    private val runtime =
        object : CollaborationRuntimeRepository by app.sdk {
            override suspend fun updateRetention(
                retention: CollaborationRuntimeRetention
            ): AppResult<Unit> {
                retentions += retention
                return onRetention(retention)
            }
        }
    private val schedules =
        object : SyncScheduleRepository {
            override suspend fun request(key: String): AppResult<Unit> {
                scheduled += key
                return AppResult.Success(Unit)
            }
        }
    val lifetime =
        ApplicationCollaborationLifetime(
            MaintainCollaborationRuntimeOwnership(
                runtime,
                app.koin.get(),
                sessions,
                workspaces,
                app.koin.get(),
                app.koin.get(),
                Clock.System,
                scheduler.timeSource,
            ),
            schedules,
            StandardTestDispatcher(scheduler),
        )

    fun published() =
        assertIs<AppResult.Success<CollaborationRuntimePublication>>(
                assertIs<ApplicationCollaborationState.Observed>(lifetime.state.value).publication
            )
            .value
}
