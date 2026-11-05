@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationSpaceSetupTest {
    @Test
    fun expiredGatewayAfterFreshParentLoginProjectsOnlyOwnedQueueSummary() = runTest {
        val f = SpaceSetupFixture()
        val original = f.prepared(CollaborationSpaceSetupKind.BOOTSTRAP)
        val completed =
            assertIs<AppResult.Success<CollaborationSpaceSetupRecord>>(
                    f.delivery()(f.owner, original.command.id)
                )
                .value
        val gateway =
            assertIs<CollaborationSpaceSetupOutcome.Bootstrapped>(completed.outcome).gateway
        f.registration.session(
            f.native.session.copy(
                id = "fresh",
                binding = f.native.session.binding.copy(serverId = f.owner.spaceId),
            )
        )
        f.native.setup.now += 1_000_000.seconds
        f.native.setup.workspace = null
        assertIs<AppResult.Failed>(f.observe()(f.access).first())
        val summaries =
            assertIs<AppResult.Success<List<CollaborationSpaceSetupSummary>>>(
                    f.observe()(f.access.copy(sessionId = "fresh")).first()
                )
                .value
        val summary = summaries.single()
        assertEquals(original.command.id, summary.id)
        assertEquals(original.command.registrationId, summary.registrationId)
        assertEquals(original.command.binding.nativeSessionId, summary.nativeSessionId)
        assertEquals(original.command.request.deviceId, summary.deviceId)
        assertEquals(CollaborationSpaceSetupKind.BOOTSTRAP, summary.kind)
        assertEquals(CollaborationSpaceSetupPhase.BOOTSTRAPPED, summary.phase)
        assertEquals(original.command.createdAt, summary.createdAt)
        assertEquals(1, summary.attempts)
        assertNull(summary.failure)
        assertFalse(summary.toString().contains(gateway.accessToken))
        assertFalse(summary.toString().contains(gateway.refreshToken))
        assertEquals(1, f.sent.size)
        assertEquals(0, f.registration.sdkReads)
    }

    @Test
    fun accountReplacementCancelsQueuedRowsAndDisposalStopsObservation() = runTest {
        val f = SpaceSetupFixture()
        f.prepared()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val values = mutableListOf<AppResult<List<CollaborationSpaceSetupSummary>>>()
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
        f.registration.session(
            f.native.session.copy(user = f.native.session.user.copy(id = f.owner.spaceId))
        )
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
    fun foreignMalformedDuplicateAndOversizedRowsCannotPublishAndStorageFailureSurvives() =
        runTest {
            val f = SpaceSetupFixture()
            val original = f.prepared()
            val request = original.command.request as CollaborationSpaceProvisioningRetry
            for (rows in
                listOf(
                    listOf(
                        original.copy(
                            command =
                                original.command.copy(
                                    request =
                                        request.copy(
                                            binding =
                                                request.binding.copy(accountId = f.owner.spaceId)
                                        )
                                )
                        )
                    ),
                    listOf(original.copy(attempts = -1)),
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
