@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceIdentityRepository
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceIdentity
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceIdentityReadTest {
    @Test
    fun absenceAndRetainedProviderUserNeedNoCompanyAndNeverSelectEnrollOrPoll() = runTest {
        val f = Fixture()
        f.native.setup.workspace = null
        assertEquals(AppResult.Success(f.observation), f.read()(f.owner))
        f.response = { AppResult.Success(f.observation.copy(identity = null)) }
        assertNull(
            assertIs<AppResult.Success<CollaborationSpaceIdentityObservation>>(f.read()(f.owner))
                .value
                .identity
        )
        advanceTimeBy(60_000)
        assertEquals(2, f.calls)
        assertEquals(0, f.directory.admits)
    }

    @Test
    fun wrongSpaceAccountOriginRealmAndVersionAreRejectedEvenFromRepository() = runTest {
        for (variant in 0..7) {
            val f = Fixture()
            val invalid =
                when (variant) {
                    0 ->
                        f.observation.copy(owner = f.owner.copy(spaceId = f.owner.parent.accountId))
                    else ->
                        f.observation.copy(
                            identity =
                                when (variant) {
                                    1 -> f.identity.copy(accountId = f.owner.spaceId)
                                    2 -> f.identity.copy(spaceId = f.owner.parent.accountId)
                                    3 ->
                                        f.identity.copy(
                                            origin = CollaborationSpaceIdentityOrigin.SPACE
                                        )
                                    4 ->
                                        f.identity.copy(
                                            matrixUserId =
                                                f.identity.matrixUserId.replace("hris_", "hrisg_")
                                        )
                                    5 -> f.identity.copy(legacyCompanyId = null)
                                    6 -> f.identity.copy(serverName = "https://elsewhere.example")
                                    else -> f.identity.copy(version = 1)
                                }
                        )
                }
            f.response = { AppResult.Success(invalid) }
            assertEquals(
                "client.collaboration_invalid_response",
                assertIs<AppResult.Failed>(f.read()(f.owner)).failure.code,
            )
        }
    }

    @Test
    fun freshLoginCanReadSelectedUserButOldOrReplacedParentsCannotPublish() = runTest {
        val f = Fixture()
        val current =
            f.owner.copy(
                parent =
                    f.owner.parent.copy(
                        sessionId = "fresh-local",
                        nativeSessionId = f.owner.spaceId,
                    )
            )
        f.native.setup.session =
            f.native.session.copy(
                id = current.parent.sessionId,
                binding = f.native.session.binding.copy(serverId = current.parent.nativeSessionId),
            )
        assertIs<AppResult.Failed>(f.read()(f.owner))
        assertEquals(0, f.calls)
        f.response = { AppResult.Success(f.observation.copy(owner = current)) }
        assertEquals(AppResult.Success(f.observation.copy(owner = current)), f.read()(current))
        f.response = {
            f.native.setup.session = null
            AppResult.Success(f.observation.copy(owner = current))
        }
        assertIs<AppResult.Failed>(f.read()(current))
        assertEquals(2, f.calls)
    }

    @Test
    fun originalHardDeadlineClockRollbackAndMonotonicLimitRejectLateMetadata() = runTest {
        for (variant in 0..2) {
            val f = Fixture()
            f.native.setup.session =
                f.native.session.copy(
                    binding =
                        f.native.session.binding.copy(
                            expiresAtEpochMillis =
                                (f.native.setup.now + 2.seconds).toEpochMilliseconds()
                        )
                )
            f.response = {
                when (variant) {
                    0 -> {
                        f.native.setup.now += 3.seconds
                        f.native.setup.session = f.native.session
                    }
                    1 -> f.native.setup.now -= 1.seconds
                    else -> f.native.time += 2.seconds
                }
                AppResult.Success(f.observation)
            }
            assertEquals(
                "client.collaboration_revalidation_required",
                assertIs<AppResult.Failed>(f.read()(f.owner)).failure.code,
            )
        }
    }

    @Test
    fun pendingCancellationDisposesReadAndNonCooperativeSuccessCannotEscapeTimeout() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.response = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val task = async { f.read()(f.owner) }
        entered.await()
        task.cancelAndJoin()
        stopped.await()
        assertTrue(task.isCancelled)
        f.response = {
            withContext(NonCancellable) { delay(20_000) }
            AppResult.Success(f.observation)
        }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.read()(f.owner)).failure.kind,
        )
    }

    @Test
    fun receivedDenialSurvivesCancelledAttemptAndFailedPersistenceThenSuccessfulMetadataKeepsGate() =
        runTest {
            val f = Fixture()
            f.directory.afterRestrict = { AppResult.Failed(f.directory.storageFailure) }
            f.response = {
                currentCoroutineContext().cancel()
                AppResult.Failed(f.directory.denied)
            }
            val task = async { f.read()(f.owner) }
            task.join()
            assertTrue(task.isCancelled)
            assertEquals(1, f.directory.restrictions)
            f.response = { AppResult.Failed(f.directory.unavailable) }
            assertEquals(f.directory.denied, assertIs<AppResult.Failed>(f.read()(f.owner)).failure)
            f.response = { awaitCancellation() }
            assertEquals(f.directory.denied, assertIs<AppResult.Failed>(f.read()(f.owner)).failure)
            f.response = { AppResult.Success(f.observation) }
            assertIs<AppResult.Success<*>>(f.read()(f.owner))
            assertEquals(
                f.directory.denied,
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                        f.directory.gate.value
                    )
                    .value
                    .denial,
            )
            assertEquals(0, f.directory.admits)
        }

    @Test
    fun newerDenialAndFinalAdmissionDisposalWinOverCompletedRead() = runTest {
        for (variant in 0..2) {
            val f = Fixture()
            var reads = 0
            f.directory.beforeAdmission = {
                if (++reads == 2)
                    when (variant) {
                        0 -> f.directory.deny()
                        1 -> f.native.setup.session = null
                        else -> f.native.time += 15.seconds
                    }
            }
            assertIs<AppResult.Failed>(f.read()(f.owner))
            assertEquals(1, f.calls)
            assertEquals(0, f.directory.admits)
        }
    }

    private class Fixture {
        val directory = SpaceDirectoryUseCaseFixture()
        val native = directory.native
        val owner = native.owner
        private val company = "50000000-0000-4000-8000-000000000001"
        val identity =
            CollaborationSpaceIdentity(
                owner.spaceId,
                owner.parent.accountId,
                "matrix.hris.internal",
                "@hris_${company.replace("-", "")}_${owner.parent.accountId.replace("-", "")}:matrix.hris.internal",
                CollaborationSpaceIdentityOrigin.LEGACY,
                company,
                0,
                native.setup.now - 100_000.seconds,
            )
        val observation = CollaborationSpaceIdentityObservation(owner, identity)
        var calls = 0
        var response: suspend () -> AppResult<CollaborationSpaceIdentityObservation> = {
            AppResult.Success(observation)
        }
        val identities =
            object : CollaborationSpaceIdentityRepository {
                override suspend fun read(
                    owner: CollaborationSpaceOwner
                ): AppResult<CollaborationSpaceIdentityObservation> {
                    calls++
                    return response()
                }

                override suspend fun select(
                    owner: CollaborationSpaceOwner,
                    command: CollaborationSpaceIdentityCommand,
                ): AppResult<CollaborationSetupReceipt> =
                    error("A read must never select an identity")
            }

        fun read() =
            ReadCollaborationSpaceIdentity(
                identities,
                directory.directory,
                native.setup.sessions,
                native.server,
                AppBuild(AppPlatform.Android, 5, "test", null),
                native.clock,
                native.time,
            )
    }
}
