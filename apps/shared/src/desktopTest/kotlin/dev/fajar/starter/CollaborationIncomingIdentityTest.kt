package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationIdentityRepository
import dev.fajar.starter.collaboration.domain.usecases.PrepareCollaborationMessageNotification
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.*

/** Actual app/DI/encrypted SQLite; background SDK and HTTP are fictional. */
class CollaborationIncomingIdentityTest {
    @Test
    fun identityDeadlineJoinsTheReaderAndStillAllowsHistoryWithinTheRoomBudget() = runBlocking {
        withFixture { f ->
            val event = f.sdk.incoming(f.first, "Fictional history after identity timeout")
            var active = false
            f.sdk.beforeIdentity = {
                active = true
                try {
                    awaitCancellation()
                } finally {
                    active = false
                }
            }
            val result =
                withTimeout(6_000) {
                    f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant())
                }
            assertNotNull(assertIs<SyncResult.Retry>(result).failure)
            assertFalse(active)
            assertEquals(event.text, f.incomingOriginal(event)?.text)
            assertEquals(1, f.sdk.identityReads)
            released(f)
        }
    }

    @Test
    fun backgroundHistoryAcquiresItsIdentityAndEnablesProjectionAcrossContainerReopen() =
        runBlocking {
            withFixture { f ->
                val event = f.sdk.incoming(f.first, "Fictional identity-backed message")
                assertEquals(
                    SyncResult.Complete,
                    f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
                )
                val access = f.incomingGrant().owner.localListAccess()
                assertEquals(
                    AppResult.Success(CollaborationSelfIdentity(f.sdk.sender)),
                    f.koin.get<CollaborationIdentityRepository>().current(access),
                )
                assertEquals(1, f.sdk.identityReads)
                f.reopen()
                f.withoutIncomingSelection()
                val scope = CollaborationLocalScope.Captured(access, f.session.binding.serverId)
                assertIs<CollaborationNotificationProjection.Ready>(
                    assertIs<AppResult.Success<CollaborationNotificationProjection>>(
                            f.koin.get<PrepareCollaborationMessageNotification>()(
                                scope,
                                f.first,
                                f.server.collaboration.roomId(f.first),
                                event.eventId,
                            )
                        )
                        .value
                )
                assertEquals(1, f.sdk.identityReads)
                assertTrue(f.sdk.enqueued.isEmpty())
                released(f)
            }
        }

    @Test
    fun missingOrUnavailableIdentityLeavesRetryButStillRetainsTheOriginal() = runBlocking {
        for (absent in listOf(false, true)) withFixture { f ->
            val event = f.sdk.incoming(f.first, "Fictional history while identity is pending")
            f.sdk.identityResult =
                if (absent) AppResult.Success(null)
                else AppResult.Failed(Failure(FailureKind.Unavailable, "Fictional unavailable SDK"))
            assertNotNull(
                assertIs<SyncResult.Retry>(
                        f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant())
                    )
                    .failure
            )
            assertEquals(event.text, f.incomingOriginal(event)?.text)
            assertEquals(
                AppResult.Success(null),
                f.koin
                    .get<CollaborationIdentityRepository>()
                    .current(f.incomingGrant().owner.localListAccess()),
            )
            f.sdk.identityResult = AppResult.Success(CollaborationSelfIdentity(f.sdk.sender))
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
            )
            released(f)
        }
    }

    @Test
    fun cancellationDuringIdentityAcquisitionReleasesTheRuntimeBeforeAnyTimelineOpens() =
        runBlocking {
            withFixture { f ->
                val entered = CompletableDeferred<Unit>()
                f.sdk.beforeIdentity = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val job = async { f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()) }
                withTimeout(5_000) { entered.await() }
                job.cancelAndJoin()
                assertEquals(1, f.sdk.identityReads)
                assertEquals(0, f.sdk.childOpens)
                assertEquals(0, f.sdk.historyReads)
                released(f)
            }
        }

    private fun released(f: CollaborationWorkerFixture) {
        assertEquals(f.sdk.backgroundAcquisitions, f.sdk.backgroundCloses)
        assertEquals(f.sdk.childOpens, f.sdk.childCloses)
        assertEquals(0, f.sdk.backgroundInterruptions.subscriptionCount.value)
        assertEquals(0, f.sdk.backgroundCredentialFailures.subscriptionCount.value)
    }

    private suspend fun withFixture(block: suspend (CollaborationWorkerFixture) -> Unit) {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.server.collaboration.listedIds = listOf(f.first)
            f.withoutIncomingSelection()
            f.sdk.backgroundEnabled = true
            block(f)
            assertTrue(f.server.unexpectedPaths.isEmpty())
        } finally {
            f.close()
        }
    }
}
