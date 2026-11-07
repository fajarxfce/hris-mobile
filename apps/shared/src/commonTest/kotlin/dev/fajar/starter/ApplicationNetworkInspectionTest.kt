package dev.fajar.starter

import dev.fajar.inspector.*
import dev.fajar.starter.app.inspection.ApplicationNetworkInspection
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.usecases.ObserveSessionIdentity
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationNetworkInspectionTest {
    @Test
    fun tokenRotationKeepsCaptureButNewLoginForTheSameUserErasesIt() = runTest {
        val original = session()
        val source = MutableStateFlow<AppResult<Session?>>(AppResult.Success(original))
        val inspection = NetworkInspection()
        val owner = owner(inspection, source, StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            val first = assertNotNull(inspection.recorders.value)
            val attempt = assertNotNull(first.begin(request()))
            source.value =
                AppResult.Success(original.copy(tokens = SessionTokens("next", "next", 200)))
            runCurrent()
            assertSame(first, inspection.recorders.value)
            assertEquals(1, first.snapshots.value.traces.size)
            source.value = AppResult.Success(original.copy(id = "replacement"))
            runCurrent()
            assertNotSame(first, inspection.recorders.value)
            assertTrue(first.snapshots.value.closed)
            assertTrue(first.snapshots.value.traces.isEmpty())
            first.finish(attempt, NetworkOutcome.Response(200))
            assertTrue(inspection.recorders.value!!.snapshots.value.traces.isEmpty())
        } finally {
            owner.closeAndJoin()
        }
    }

    @Test
    fun accountReplacementAndLogoutEachRotateCapture() = runTest {
        val source = MutableStateFlow<AppResult<Session?>>(AppResult.Success(session()))
        val inspection = NetworkInspection()
        val owner = owner(inspection, source, StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            val original = assertNotNull(inspection.recorders.value)
            source.value =
                AppResult.Success(
                    session().copy(user = User("two", "Second", "second@example.invalid"))
                )
            runCurrent()
            val replacement = assertNotNull(inspection.recorders.value)
            assertNotSame(original, replacement)
            source.value = AppResult.Success(null)
            runCurrent()
            assertTrue(original.snapshots.value.closed)
            assertTrue(replacement.snapshots.value.closed)
            assertNotSame(replacement, inspection.recorders.value)
        } finally {
            owner.closeAndJoin()
        }
    }

    @Test
    fun sourceFailureClosesDiagnosticsWithoutPropagatingToTheApplication() = runTest {
        val inspection = NetworkInspection()
        val original = assertNotNull(inspection.recorders.value)
        val owner =
            owner(
                inspection,
                flow {
                    emit(AppResult.Success(session()))
                    error("fixture failure")
                },
                StandardTestDispatcher(testScheduler),
            )
        runCurrent()
        owner.closeAndJoin()
        assertNull(inspection.recorders.value)
        assertTrue(original.snapshots.value.closed)
        assertFalse(inspection.reset())
    }

    @Test
    fun sourceCompletionAlsoClosesCapture() = runTest {
        val inspection = NetworkInspection()
        val owner =
            owner(
                inspection,
                flowOf(AppResult.Success(session())),
                StandardTestDispatcher(testScheduler),
            )
        runCurrent()
        owner.closeAndJoin()
        assertNull(inspection.recorders.value)
    }

    @Test
    fun disposalCancelsAPendingIdentityReadAndCannotBeReopened() = runTest {
        val inspection = NetworkInspection()
        var disposed = false
        val owner =
            owner(
                inspection,
                flow {
                    try {
                        emit(AppResult.Success(session()))
                        awaitCancellation()
                    } finally {
                        disposed = true
                    }
                },
                StandardTestDispatcher(testScheduler),
            )
        runCurrent()
        owner.closeAndJoin()
        owner.closeAndJoin()
        assertTrue(disposed)
        assertNull(inspection.recorders.value)
        assertFalse(inspection.reset())
    }

    @Test
    fun lateIdentityCleanupCannotRetainMetadataAfterClose() = runTest {
        val inspection = NetworkInspection()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val owner =
            owner(
                inspection,
                flow {
                    try {
                        emit(AppResult.Success(session()))
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                },
                StandardTestDispatcher(testScheduler),
            )
        try {
            runCurrent()
            owner.close()
            runCurrent()
            assertTrue(entered.isCompleted)
            assertNull(inspection.recorders.value)
            assertFalse(inspection.reset())
        } finally {
            release.complete(Unit)
            owner.closeAndJoin()
        }
    }

    private fun owner(
        inspection: NetworkInspection,
        source: Flow<AppResult<Session?>>,
        dispatcher: CoroutineDispatcher,
    ): ApplicationNetworkInspection {
        val repository =
            object : SessionRepository {
                override val persistent = false

                override fun observe() = source

                override suspend fun current(): AppResult<Session?> = error("Observation only")

                override suspend fun compareAndSet(
                    expected: Session?,
                    updated: Session?,
                ): AppResult<Boolean> = error("Observation only")

                override suspend fun clear(sessionId: String): AppResult<Boolean> =
                    error("Observation only")
            }
        return ApplicationNetworkInspection(
            inspection,
            ObserveSessionIdentity(repository),
            dispatcher,
        )
    }

    private fun session() =
        Session(
            "session",
            User("one", "Example", "one@example.invalid"),
            SessionTokens("access", "refresh", 100),
            SessionBinding("family", "https://example.invalid/", 1_000),
        )

    private fun request() =
        NetworkRequest(
            NetworkSource.KTOR,
            "GET",
            NetworkEndpoint("https", "example.invalid", path = "/items"),
        )
}
