@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceConversationCommandRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceConversationCommands
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SpaceConversationCommandObservationTest {
    @Test
    fun expiredNativeStateAndCompanyChangesDoNotHideOwnedCommandsOrRestartObservation() = runTest {
        val f = Fixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        f.base.native.setup.beforeWorkspace = { error("No company acquisition") }
        f.base.native.onPage = { error("No network acquisition") }
        f.base.deny()
        val values = mutableListOf<AppResult<List<CollaborationSpaceConversationCommandRecord>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.base.access).toList(values)
            }
        runCurrent()
        assertEquals(listOf(f.snapshots.value), values)
        f.base.identity.value =
            AppResult.Success(
                f.base.native.session.copy(
                    tokens = f.base.native.session.tokens.copy(accessToken = "fictional-rotated"),
                    binding =
                        f.base.native.session.binding.copy(
                            serverId = f.base.native.contact.accountId
                        ),
                )
            )
        runCurrent()
        assertEquals(1, f.starts)
        assertEquals(1, values.size)
        assertEquals(0, f.base.native.pageCalls)
        job.cancelAndJoin()
        assertEquals(1, f.stops)
    }

    @Test
    fun accountLoginAndAudienceReplacementStopThePreviousCommandCollection() = runTest {
        for (replacement in 0..3) {
            val f = Fixture()
            val values =
                mutableListOf<AppResult<List<CollaborationSpaceConversationCommandRecord>>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.base.access).toList(values)
                }
            runCurrent()
            val session = f.base.native.session
            f.base.identity.value =
                AppResult.Success(
                    when (replacement) {
                        0 -> null
                        1 -> session.copy(id = "new-local-login")
                        2 ->
                            session.copy(
                                user = session.user.copy(id = f.base.native.contact.accountId)
                            )
                        else ->
                            session.copy(
                                binding =
                                    session.binding.copy(audience = "https://other.example.test/")
                            )
                    }
                )
            runCurrent()
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(values.last()).failure.kind,
            )
            assertEquals(1, f.stops)
            f.snapshots.value = AppResult.Success(emptyList())
            runCurrent()
            assertEquals(2, values.size)
            assertIs<AppResult.Failed>(values.last())
            job.cancelAndJoin()
        }
    }

    @Test
    fun queuedOldFactsCannotEscapeAfterOwnershipChangesDuringBackpressure() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val values = mutableListOf<AppResult<List<CollaborationSpaceConversationCommandRecord>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.base.access).collect {
                    values += it
                    if (values.size == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            }
        entered.await()
        f.snapshots.value = AppResult.Success(emptyList())
        runCurrent()
        f.base.identity.value = AppResult.Success(null)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(2, values.size)
        assertIs<AppResult.Failed>(values.last())
        assertEquals(1, f.stops)
        job.cancelAndJoin()
    }

    @Test
    fun failedIdentityAndInvalidOwnershipNeverReadCommands() = runTest {
        val f = Fixture()
        f.base.identity.value = AppResult.Failed(f.base.storageFailure)
        assertEquals(AppResult.Failed(f.base.storageFailure), f.observe()(f.base.access).first())
        f.base.identity.value = AppResult.Success(f.base.native.session)
        for (access in
            listOf(
                f.base.access.copy(sessionId = "other"),
                f.base.access.copy(accountId = f.base.native.contact.accountId),
                f.base.access.copy(audience = "https://other.example.test/"),
            )) {
            assertIs<AppResult.Failed>(f.observe()(access).first())
        }
        assertEquals(0, f.starts)
    }

    @Test
    fun storageFailureRetainsTheSubscriptionWithoutStartingBusinessRetry() = runTest {
        val f = Fixture()
        val original = f.snapshots.value
        val values = mutableListOf<AppResult<List<CollaborationSpaceConversationCommandRecord>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.base.access).toList(values)
            }
        runCurrent()
        f.snapshots.value = AppResult.Failed(f.base.storageFailure)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.storageFailure), values.last())
        f.snapshots.value = original
        runCurrent()
        assertEquals(listOf(original, AppResult.Failed(f.base.storageFailure), original), values)
        assertEquals(1, f.starts)
        job.cancelAndJoin()
        assertEquals(1, f.stops)
    }

    @Test
    fun disposalCancelsAPendingReadAndANewLoginCanReopenTheSameImmutableCommand() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        f.source = {
            flow {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.base.access).collect()
            }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, f.stops)
        f.source = { f.snapshots }
        val current = f.base.access.copy(sessionId = "replacement-login")
        f.base.identity.value =
            AppResult.Success(f.base.native.session.copy(id = current.sessionId))
        assertEquals(f.snapshots.value, f.observe()(current).first())
        assertEquals(2, f.starts)
        assertEquals(2, f.stops)
    }

    @Test
    fun pendingAndRecordedSetupFactsRemainReadableWithoutReopeningDirectoryAccess() = runTest {
        val f = Fixture()
        f.base.deny()
        val pending =
            CollaborationSpaceConversationCommandRecord(
                f.command,
                0,
                0,
                CollaborationSpaceConversationOutcome.Pending,
            )
        val recorded =
            pending.copy(
                revision = 2,
                attempts = 1,
                outcome =
                    CollaborationSpaceConversationOutcome.Recorded(
                        CollaborationSetupReceipt("90000000-0000-4000-8000-000000000001", 0)
                    ),
            )
        f.snapshots.value = AppResult.Success(listOf(pending))
        val values = mutableListOf<AppResult<List<CollaborationSpaceConversationCommandRecord>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.base.access).toList(values)
            }
        runCurrent()
        f.snapshots.value = AppResult.Success(listOf(recorded))
        runCurrent()
        assertEquals(
            listOf<AppResult<List<CollaborationSpaceConversationCommandRecord>>>(
                AppResult.Success(listOf(pending)),
                AppResult.Success(listOf(recorded)),
            ),
            values,
        )
        assertEquals(0, f.base.admits)
        assertEquals(0, f.base.native.pageCalls)
        assertEquals(
            f.base.denied,
            assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(f.base.gate.value)
                .value
                .denial,
        )
        job.cancelAndJoin()
    }

    private class Fixture {
        val base = SpaceDirectoryUseCaseFixture()
        val command =
            CollaborationSpaceConversationCommand.Open(
                "80000000-0000-4000-8000-000000000001",
                base.owner.identityBinding(),
                base.native.contact.accountId,
                base.native.setup.now,
            )
        val snapshots =
            MutableStateFlow<AppResult<List<CollaborationSpaceConversationCommandRecord>>>(
                AppResult.Success(
                    listOf(
                        CollaborationSpaceConversationCommandRecord(
                            command,
                            1,
                            1,
                            CollaborationSpaceConversationOutcome.Unconfirmed(base.denied),
                        )
                    )
                )
            )
        var starts = 0
        var stops = 0
        var source: () -> Flow<AppResult<List<CollaborationSpaceConversationCommandRecord>>> = {
            snapshots
        }
        val commands =
            object : CollaborationSpaceConversationCommandRepository {
                override fun observeAll(access: CollaborationSpaceLocalAccess) = flow {
                    assertEquals(base.access.accountId, access.accountId)
                    assertEquals(base.access.spaceId, access.spaceId)
                    starts++
                    try {
                        emitAll(source())
                    } finally {
                        stops++
                    }
                }

                override suspend fun find(
                    access: CollaborationSpaceLocalAccess,
                    id: String,
                ): AppResult<CollaborationSpaceConversationCommandRecord?> =
                    error("Observation must not look up or replay commands")

                override suspend fun insert(
                    access: CollaborationSpaceLocalAccess,
                    command: CollaborationSpaceConversationCommand,
                ): AppResult<CollaborationSetupInsertion> =
                    error("Observation must not prepare commands")

                override suspend fun update(
                    access: CollaborationSpaceLocalAccess,
                    expected: CollaborationSpaceConversationCommandRecord,
                    outcome: CollaborationSpaceConversationOutcome,
                    attempts: Int,
                ): AppResult<Boolean> = error("Observation must not mutate commands")

                override suspend fun archiveRecorded(
                    access: CollaborationSpaceLocalAccess,
                    expected: CollaborationSpaceConversationCommandRecord,
                ): AppResult<Boolean> = error("Observation must not archive commands")
            }

        fun observe() =
            ObserveCollaborationSpaceConversationCommands(
                commands,
                base.sessions,
                base.native.server,
            )
    }
}
