@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRootRepository
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationSpaceRootUseCaseTest {
    @Test
    fun savedRootReadsIgnoreAgeAndCompanyAndSameOwnerRotationKeepsOneSubscription() = runTest {
        val f = RootFixture()
        f.facts.value = AppResult.Success(f.root)
        f.native.setup.now += 365.days
        f.native.setup.workspace = null
        f.native.setup.beforeWorkspace = { error("No company scope") }
        val values = mutableListOf<AppResult<CollaborationSpaceRoot?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        runCurrent()
        assertEquals(AppResult.Success(f.root), values.single())
        f.session(
            f.native.session.copy(
                tokens = f.native.session.tokens.copy(accessToken = "rotated"),
                binding = f.native.session.binding.copy(serverId = f.root.spaceId),
            )
        )
        runCurrent()
        assertEquals(1, f.observations)
        assertEquals(1, values.size)
        assertEquals(0, f.native.discoveryCalls + f.native.pageCalls)
        job.cancelAndJoin()
        assertEquals(1, f.stops)
    }

    @Test
    fun replacingAccountDiscardsQueuedOldRootWhileConsumerIsSuspended() = runTest {
        val f = RootFixture()
        f.facts.value = AppResult.Success(f.root)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val values = mutableListOf<AppResult<CollaborationSpaceRoot?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).collect {
                    values += it
                    if (values.size == 1) {
                        entered.complete(Unit)
                        resume.await()
                    }
                }
            }
        entered.await()
        f.facts.value = AppResult.Success(null)
        runCurrent()
        f.session(f.native.session.copy(user = f.native.session.user.copy(id = f.root.spaceId)))
        runCurrent()
        resume.complete(Unit)
        runCurrent()
        assertEquals(2, values.size)
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(1, f.stops)
        job.cancelAndJoin()
    }

    @Test
    fun invalidAccountAccessCannotOpenStorageAndSourceFailureNeedsExplicitReobservation() =
        runTest {
            val f = RootFixture()
            for (access in
                listOf(
                    f.access.copy(sessionId = ""),
                    f.access.copy(audience = "https://foreign.example.test/"),
                    f.access.copy(accountId = "invalid"),
                )) assertIs<AppResult.Failed>(f.observe()(access).first())
            assertEquals(0, f.observations)
            val failed = Failure(FailureKind.Storage, "Fixture unavailable")
            f.read = { flowOf(AppResult.Failed(failed)) }
            val values = mutableListOf<AppResult<CollaborationSpaceRoot?>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access).toList(values)
                }
            runCurrent()
            assertEquals(failed, assertIs<AppResult.Failed>(values.single()).failure)
            f.read = { f.facts }
            f.session(
                f.native.session.copy(
                    tokens = f.native.session.tokens.copy(accessToken = "rotated")
                )
            )
            runCurrent()
            assertEquals(1, f.observations)
            job.cancelAndJoin()
            assertEquals(AppResult.Success(null), f.observe()(f.access).first())
            assertEquals(2, f.observations)
        }

    @Test
    fun publishingFreshDiscoveryIsLocalAndConflictingRootRequiresMigration() = runTest {
        val f = RootFixture()
        val discovery = f.discover()
        assertEquals(AppResult.Success(f.root), f.publish()(discovery))
        assertEquals(AppResult.Success(f.root), f.publish()(discovery))
        assertEquals(1, f.native.discoveryCalls)
        assertEquals(0, f.native.pageCalls)
        val original = f.root.copy(spaceId = f.native.contact.accountId)
        f.facts.value = AppResult.Success(original)
        val conflict = assertIs<AppResult.Failed>(f.publish()(discovery))
        assertEquals(FailureKind.Conflict, conflict.failure.kind)
        assertEquals("client.collaboration_space_migration_required", conflict.failure.code)
        assertEquals(AppResult.Success(original), f.facts.value)
    }

    @Test
    fun refusedPublicationReconcilesStoredRootWithoutInventingAMigration() = runTest {
        for (mode in 0..2) {
            val f = RootFixture()
            val discovery = f.discover()
            val storageFailure = Failure(FailureKind.Storage, "Fixture read failed")
            f.beforeRetain = {
                f.facts.value =
                    when (mode) {
                        0 -> AppResult.Success(null)
                        1 -> AppResult.Success(f.root)
                        else -> AppResult.Failed(storageFailure)
                    }
                AppResult.Success(false)
            }
            val result = f.publish()(discovery)
            when (mode) {
                0 -> {
                    val failure = assertIs<AppResult.Failed>(result).failure
                    assertEquals(FailureKind.Conflict, failure.kind)
                    assertEquals("client.collaboration_root_changed", failure.code)
                }
                1 -> assertEquals(AppResult.Success(f.root), result)
                else -> assertEquals(storageFailure, assertIs<AppResult.Failed>(result).failure)
            }
            assertEquals(1, f.retains)
            assertEquals(1, f.native.discoveryCalls)
            assertEquals(0, f.native.pageCalls)
        }
    }

    @Test
    fun expiredOrMalformedDiscoveryNeverPublishesButOwnedRootStaysReadable() = runTest {
        for (mode in 0..4) {
            val f = RootFixture()
            val original = f.discover()
            f.facts.value = AppResult.Success(f.root)
            val discovery =
                when (mode) {
                    0 -> {
                        f.native.time += 61.seconds
                        original
                    }
                    1 -> {
                        f.native.setup.now -= 1.seconds
                        original
                    }
                    2 -> original.copy(expiresAt = original.expiresAt + 1.seconds)
                    3 ->
                        original.copy(
                            observation = original.observation.copy(nativeSessionVersion = -1)
                        )
                    else -> {
                        f.native.setup.now += 365.days
                        original
                    }
                }
            assertIs<AppResult.Failed>(f.publish()(discovery))
            assertEquals(0, f.retains)
            assertEquals(AppResult.Success(f.root), f.observe()(f.access).first())
        }
    }

    @Test
    fun changedNativeParentBeforeOrDuringPublicationNeverReturnsSuccess() = runTest {
        for (during in listOf(false, true)) {
            val f = RootFixture()
            val discovery = f.discover()
            fun replace() {
                f.session(
                    f.native.session.copy(
                        binding = f.native.session.binding.copy(serverId = f.root.spaceId)
                    )
                )
            }
            if (during)
                f.beforeRetain = {
                    replace()
                    AppResult.Success(true)
                }
            else replace()
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.publish()(discovery)).failure.kind,
            )
            assertEquals(if (during) 1 else 0, f.retains)
        }
    }

    @Test
    fun pendingPublicationHasAFiniteBudgetAndCancellationStopsStorage() = runTest {
        for (cancel in listOf(false, true)) {
            val f = RootFixture()
            val discovery = f.discover()
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            f.beforeRetain = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
            val request = async { f.publish()(discovery) }
            entered.await()
            if (cancel) request.cancelAndJoin()
            else
                assertEquals(
                    FailureKind.Timeout,
                    assertIs<AppResult.Failed>(request.await()).failure.kind,
                )
            stopped.await()
            assertEquals(AppResult.Success(null), f.facts.value)
        }
    }

    @Test
    fun lateCancelledStorageCompletionCannotBecomeSuccessfulPublication() = runTest {
        val f = RootFixture()
        val discovery = f.discover()
        f.beforeRetain = {
            currentCoroutineContext().cancel()
            AppResult.Success(true)
        }
        val request = async { f.publish()(discovery) }
        assertFailsWith<CancellationException> { request.await() }
    }
}

private class RootFixture {
    val native = CollaborationSpaceFixture()
    val access = native.parent.localSpaceAccountAccess()
    val root = CollaborationSpaceRoot(native.owner.spaceId)
    val facts = MutableStateFlow<AppResult<CollaborationSpaceRoot?>>(AppResult.Success(null))
    val identity = MutableStateFlow<AppResult<Session?>>(AppResult.Success(native.session))
    var observations = 0
    var stops = 0
    var retains = 0
    var beforeRetain: suspend () -> AppResult<Boolean> = { AppResult.Success(true) }
    var read: () -> Flow<AppResult<CollaborationSpaceRoot?>> = { facts }
    val sessions =
        object : SessionRepository by native.setup.sessions {
            override fun observe() = identity
        }
    val roots =
        object : CollaborationSpaceRootRepository {
            override fun observe(access: CollaborationSpaceAccountAccess) = flow {
                observations++
                try {
                    emitAll(read())
                } finally {
                    stops++
                }
            }

            override suspend fun current(access: CollaborationSpaceAccountAccess) = facts.value

            override suspend fun retain(
                access: CollaborationSpaceAccountAccess,
                root: CollaborationSpaceRoot,
            ): AppResult<Boolean> {
                retains++
                val result = beforeRetain()
                if (result !is AppResult.Success || !result.value) return result
                val current = (facts.value as AppResult.Success).value
                if (current != null && current != root) return AppResult.Success(false)
                facts.value = AppResult.Success(root)
                return AppResult.Success(true)
            }
        }

    fun session(value: Session?) {
        native.setup.session = value
        identity.value = AppResult.Success(value)
    }

    suspend fun discover() =
        assertIs<AppResult.Success<CollaborationSpaceDiscovery>>(native.discovery()(native.parent))
            .value

    fun observe() = ObserveKnownCollaborationSpace(roots, sessions, native.server)

    fun publish() =
        PublishKnownCollaborationSpace(
            roots,
            sessions,
            native.server,
            AppBuild(AppPlatform.Android, 5, "test", null),
            native.clock,
        )
}
