@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationSpaceRegistrationsTest {
    @Test
    fun oldNativeOperationsRemainLocalFactsAfterFreshLoginExpiryAndCompanyRemoval() = runTest {
        val f = SpaceRegistrationFixture()
        val original = f.prepared()
        val fresh =
            f.owner.copy(
                parent = f.owner.parent.copy(sessionId = "fresh", nativeSessionId = f.owner.spaceId)
            )
        f.session(
            f.native.session.copy(
                id = fresh.parent.sessionId,
                binding = f.native.session.binding.copy(serverId = fresh.parent.nativeSessionId),
            )
        )
        f.native.setup.now += 1_000_000.seconds
        f.native.setup.workspace = null
        assertEquals(
            AppResult.Success(listOf(original)),
            f.observe()(fresh.localDirectoryAccess()).first(),
        )
        assertEquals(1, f.sdkReads)
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun accountReplacementCancelsQueuedRowsAndDisposalStopsObservation() = runTest {
        val f = SpaceRegistrationFixture()
        f.prepared()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val values = mutableListOf<AppResult<List<CollaborationSpaceRegistrationRecord>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).collect {
                    values += it
                    if (values.size == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            }
        entered.await()
        f.records.value = AppResult.Success(null)
        runCurrent()
        f.session(f.native.session.copy(user = f.native.session.user.copy(id = f.owner.spaceId)))
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(2, values.size)
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        job.cancelAndJoin()
        assertEquals(1, f.stops)
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun malformedForeignDuplicateAndOversizedRepositoryRowsCannotPublish() = runTest {
        val f = SpaceRegistrationFixture()
        val original = f.prepared()
        for (rows in
            listOf(
                listOf(
                    original.copy(
                        command =
                            original.command.copy(
                                binding = original.command.binding.copy(accountId = f.owner.spaceId)
                            )
                    )
                ),
                listOf(original, original),
                List(129) { original },
            )) {
            f.observeRecords = { flowOf(AppResult.Success(rows)) }
            assertIs<AppResult.Failed>(f.observe()(f.access).first())
        }
        f.observeRecords = { flowOf(AppResult.Failed(f.base.storageFailure)) }
        assertEquals(AppResult.Failed(f.base.storageFailure), f.observe()(f.access).first())
        assertTrue(f.sent.isEmpty())
    }
}
