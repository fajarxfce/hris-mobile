package dev.fajar.starter

import dev.fajar.starter.app.work.ApplicationPushReceiver
import dev.fajar.starter.app.work.CollaborationPriorityRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.fixtures.NativeUiServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.usecases.SignIn
import dev.fajar.starter.sync.domain.SyncResult
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

class CollaborationPriorityRecoveryTest {
    @Test
    fun expiredNativeCredentialsKeepOwnedPriorityWithoutContactingTheServer() = runBlocking {
        withFixture { f ->
            val event =
                f.sdk.incoming(f.first, "Fictional retained priority during session renewal")
            f.koin.get<ApplicationPushReceiver>().queue(f.fields(event))
            val before = f.readPriority()
            val sessions = f.koin.get<SessionRepository>()
            val expired =
                object : SessionRepository by sessions {
                    override suspend fun current():
                        AppResult<dev.fajar.starter.identity.domain.entities.Session?> =
                        AppResult.Success(
                            f.session.copy(
                                binding = f.session.binding.copy(expiresAtEpochMillis = 0)
                            )
                        )
                }
            val admission =
                AcquireCollaborationBackgroundAdmission(
                    f.koin.get(),
                    f.koin.get(),
                    expired,
                    f.koin.get(),
                    f.koin.get(),
                    kotlin.time.Clock.System,
                )
            val processor =
                CollaborationPriorityRecovery(f.koin.get(), f.koin.get(), admission, f.koin.get())
            f.server.collaboration.reads.clear()
            assertIs<SyncResult.Blocked>(processor(before.parent))
            assertEquals(before, f.readPriority())
            assertTrue(f.server.collaboration.reads.isEmpty())
            assertEquals(0, f.sdk.backgroundAcquisitions)
        }
    }

    @Test
    fun actualContainerAcquiresHintedRoomOutsideDirectoryAndRetainsOriginalAcrossReopen() =
        runBlocking {
            withFixture { f ->
                val event = f.sdk.incoming(f.first, "Fictional priority message")
                val fields = f.fields(event)
                assertIs<AppResult.Success<*>>(f.koin.get<ApplicationPushReceiver>().queue(fields))
                f.reopen()
                f.withoutIncomingSelection()
                val before = f.readPriority()
                assertEquals(1, before.priority!!.hints.size)
                assertEquals(
                    SyncResult.Complete,
                    f.koin.get<CollaborationPriorityRecovery>()(before.parent),
                )
                assertEquals(event.text, f.incomingOriginal(event)?.text)
                assertTrue(f.readPriority().priority!!.hints.isEmpty())
                assertFalse(f.server.collaboration.reads.any { it.endsWith("/summaries") })
                assertTrue(f.server.collaboration.reads.any { it.endsWith("/summary-by-room") })
                assertTrue(f.sdk.providerBounds.isEmpty())
                assertNull(
                    (f.koin.get<ReadCollaborationRoomRecovery>()(
                            f.incomingGrant().owner.localListAccess()
                        )
                            as AppResult.Success)
                        .value
                        .progress
                )
                assertTrue(f.sdk.enqueued.isEmpty())
                assertEquals(0, f.sdk.deliveries)
                f.assertReleased()
                f.reopen()
                assertIs<AppResult.Success<*>>(f.koin.get<SessionRepository>().current())
                assertEquals(event.text, f.incomingOriginal(event)?.text)
            }
        }

    @Test
    fun independentlyChangedRoomBindingCannotOpenSdkOrPretendTheHintIsContent() = runBlocking {
        withFixture { f ->
            val event = f.sdk.incoming(f.first, "Fictional retained message")
            f.koin.get<ApplicationPushReceiver>().queue(f.fields(event))
            f.server.collaboration.returnedRoomId = "!other:example.test"
            assertIs<SyncResult.Blocked>(
                f.koin.get<CollaborationPriorityRecovery>()(f.readPriority().parent)
            )
            assertEquals(0, f.sdk.backgroundAcquisitions)
            assertEquals(0, f.sdk.historyReads)
            assertNull(f.incomingOriginal(event))
            assertTrue(f.server.collaboration.roomRequests.isEmpty())
        }
    }

    @Test
    fun confirmedAdmissionDenialRetainsHintAndKeepsReadGateClosed() = runBlocking {
        withFixture { f ->
            val event = f.sdk.incoming(f.first, "Fictional denied priority")
            f.koin.get<ApplicationPushReceiver>().queue(f.fields(event))
            f.server.collaboration.admissionFailureCode = "permission_denied"
            assertIs<SyncResult.Blocked>(
                f.koin.get<CollaborationPriorityRecovery>()(f.readPriority().parent)
            )
            val gate =
                (f.koin
                        .get<CollaborationListRepository>()
                        .admission(f.incomingGrant().owner.localListAccess()) as AppResult.Success)
                    .value
            assertEquals("permission_denied", gate.denial?.code)
            assertEquals(0, f.sdk.backgroundAcquisitions)
            assertEquals(1, f.readPriority().priority!!.hints.size)
        }
    }

    @Test
    fun unresolvedOriginalAndFocusCapacityKeepHintForLaterOwnedRecovery() = runBlocking {
        for (capacity in listOf(false, true)) withFixture { f ->
            val event = f.sdk.incoming(f.first, "Fictional pending original")
            f.koin.get<ApplicationPushReceiver>().queue(f.fields(event))
            val before = f.readPriority()
            if (capacity) f.sdk.historyCapacityOnce = true
            else f.sdk.withheldHistory += event.eventId
            assertIs<SyncResult.Retry>(f.koin.get<CollaborationPriorityRecovery>()(before.parent))
            assertEquals(before, f.readPriority())
            assertNull(f.incomingOriginal(event))
            f.assertReleased()
            f.sdk.withheldHistory.clear()
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationPriorityRecovery>()(before.parent),
            )
            assertEquals(event.text, f.incomingOriginal(event)?.text)
            f.assertReleased()
        }
    }

    @Test
    fun newerPushDuringAcquisitionCannotBeAcknowledgedByTheCapturedWorker() = runBlocking {
        withFixture { f ->
            val event = f.sdk.incoming(f.first, "Fictional first hint")
            val fields = f.fields(event)
            f.koin.get<ApplicationPushReceiver>().queue(fields)
            val before = f.readPriority()
            f.sdk.beforeHistory = {
                f.sdk.beforeHistory = {}
                f.koin.get<ApplicationPushReceiver>().queue(fields + ("revision" to "2"))
            }
            assertEquals(
                SyncResult.Retry(),
                f.koin.get<CollaborationPriorityRecovery>()(before.parent),
            )
            assertEquals(2L, f.readPriority().priority!!.hints.single().revision)
            assertEquals(event.text, f.incomingOriginal(event)?.text)
            f.assertReleased()
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationPriorityRecovery>()(before.parent),
            )
        }
    }

    @Test
    fun cancellationReleasesOwnedRuntimeAndRetainsHintAcrossContainerRestart() = runBlocking {
        withFixture { f ->
            val event = f.sdk.incoming(f.first, "Fictional interrupted priority")
            f.koin.get<ApplicationPushReceiver>().queue(f.fields(event))
            val before = f.readPriority()
            val entered = CompletableDeferred<Unit>()
            f.sdk.beforeHistory = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val job = async { f.koin.get<CollaborationPriorityRecovery>()(before.parent) }
            withTimeout(5.seconds) { entered.await() }
            job.cancelAndJoin()
            f.assertReleased()
            assertEquals(before, f.readPriority())
            f.sdk.beforeHistory = {}
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationPriorityRecovery>()(before.parent),
            )
            assertEquals(event.text, f.incomingOriginal(event)?.text)
        }
    }

    private suspend fun withFixture(block: suspend (CollaborationWorkerFixture) -> Unit) {
        val f = CollaborationWorkerFixture()
        try {
            assertIs<AppResult.Success<*>>(
                f.koin.get<SignIn>()(
                    "alex@example.com",
                    "Fictional-password-123!",
                    UUID.randomUUID().toString(),
                )
            )
            f.session =
                assertNotNull(
                    (f.koin.get<SessionRepository>().current() as AppResult.Success).value
                )
            f.server.collaboration.nativeSessionId = f.session.binding.serverId
            f.server.collaboration.listedIds = emptyList()
            f.withoutIncomingSelection()
            f.sdk.backgroundEnabled = true
            block(f)
            assertTrue(f.server.unexpectedPaths.isEmpty())
        } finally {
            f.close()
        }
    }

    private fun CollaborationWorkerFixture.fields(
        event: CollaborationHistoryChange.Text
    ): Map<String, String> {
        val now = System.currentTimeMillis() / 1000
        return mapOf(
            "schemaVersion" to "1",
            "eventCode" to "collaboration.chat_available",
            "eventId" to UUID.randomUUID().toString(),
            "accountId" to session.user.id,
            "companyId" to NativeUiServer.COMPANY_ONE,
            "nativeSessionId" to session.binding.serverId,
            "deviceId" to server.collaboration.deviceId,
            "revision" to "1",
            "issuedAt" to now.toString(),
            "expiresAt" to (now + 300).toString(),
            "matrixRoomId" to server.collaboration.roomId(first),
            "matrixEventId" to event.eventId,
        )
    }

    private suspend fun CollaborationWorkerFixture.readPriority() =
        (koin.get<ReadCollaborationPushPriority>()() as AppResult.Success).value

    private fun CollaborationWorkerFixture.assertReleased() {
        assertEquals(sdk.backgroundAcquisitions, sdk.backgroundCloses)
        assertEquals(sdk.childOpens, sdk.childCloses)
        assertEquals(0, sdk.restores)
    }
}
