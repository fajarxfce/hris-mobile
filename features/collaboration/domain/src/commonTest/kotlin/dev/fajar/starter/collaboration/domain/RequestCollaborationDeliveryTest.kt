package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.CollaborationListAdmission
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RequestCollaborationDeliveryTest {
    @Test
    fun expiredLiveCredentialsCanWakeOwnedWorkWithoutNativeOrMessageReads() = runTest {
        val f = MessageIntentFixture()
        f.setup.now += 30.days
        assertEquals(AppResult.Success(Unit), request(f)("selection"))
        assertEquals(listOf(QueueCollaborationText.DELIVERY_KEY), f.scheduledKeys)
        assertEquals(0, f.messageReads)
        assertEquals(0, f.messageObservations)
        assertEquals(0, f.setup.calls)
    }

    @Test
    fun missingOwnerAndKnownDenialDoNotSchedule() = runTest {
        for (logout in listOf(true, false)) {
            val f = MessageIntentFixture()
            if (logout) f.setup.session = null
            else f.gate.value = CollaborationListAdmission("denied", f.denied)
            assertIs<AppResult.Failed>(request(f)("selection"))
            assertTrue(f.scheduledKeys.isEmpty())
        }
    }

    @Test
    fun explicitWakeReportsSchedulerFailureAndBoundedTimeoutWithoutChangingCommands() = runTest {
        val f = MessageIntentFixture()
        f.schedule = { AppResult.Failed(f.storage) }
        assertEquals(AppResult.Failed(f.storage), request(f)("selection"))
        f.schedule = { awaitCancellation() }
        val timed = assertIs<AppResult.Failed>(request(f)("selection"))
        assertEquals("client.collaboration_schedule_timeout", timed.failure.code)
        assertEquals(1_500, testScheduler.currentTime)
        assertTrue(f.records.value.isEmpty())
    }

    @Test
    fun lateSchedulingCannotPublishAcrossCancellationOrOwnerLoss() = runTest {
        val f = MessageIntentFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.schedule = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
            AppResult.Success(Unit)
        }
        val job = async { request(f)("selection") }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        f.schedule = {
            f.setup.session = null
            AppResult.Success(Unit)
        }
        assertIs<AppResult.Failed>(request(f)("selection"))
        assertEquals(2, f.scheduledKeys.size)
    }

    private fun request(f: MessageIntentFixture) =
        RequestCollaborationDelivery(
            f.lists,
            f.setup.sessions,
            f.setup.workspaces,
            f.server,
            f.build,
            f.clock,
            f.schedules,
        )
}
