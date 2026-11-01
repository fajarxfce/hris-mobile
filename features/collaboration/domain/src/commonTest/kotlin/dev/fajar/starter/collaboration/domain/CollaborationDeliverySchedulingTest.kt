package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationText
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationDeliverySchedulingTest {
    @Test
    fun committedIntentSurvivesSchedulerFailureAndExactReplayWakesAgain() = runTest {
        val f = MessageIntentFixture()
        f.schedule = { AppResult.Failed(f.storage) }
        val first = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.queue()).value
        assertEquals(listOf(QueueCollaborationText.DELIVERY_KEY), f.scheduledKeys)
        assertEquals(
            first,
            assertIs<AppResult.Success<CollaborationMessageRecord>>(f.queue()).value,
        )
        assertEquals(2, f.scheduledKeys.size)
        assertEquals(1, f.insertions)
        assertEquals(0, f.setup.calls)
    }

    @Test
    fun schedulingTimeoutDoesNotTurnALocalCommitIntoAnUncertainWrite() = runTest {
        val f = MessageIntentFixture()
        f.schedule = { awaitCancellation() }
        assertIs<AppResult.Success<CollaborationMessageRecord>>(f.queue())
        assertEquals(1_500L, testScheduler.currentTime)
        assertEquals(1, f.records.value.size)
    }

    @Test
    fun changedIntentAndUnconfirmedStorageReplyNeverRequestTransport() = runTest {
        val f = MessageIntentFixture()
        f.insertResult = AppResult.Failed(f.storage)
        assertIs<AppResult.Failed>(f.queue())
        assertTrue(f.scheduledKeys.isEmpty())
        assertIs<AppResult.Failed>(f.queue("different body"))
        assertTrue(f.scheduledKeys.isEmpty())
        assertIs<AppResult.Success<CollaborationMessageRecord>>(f.queue())
        assertEquals(listOf(QueueCollaborationText.DELIVERY_KEY), f.scheduledKeys)
    }

    @Test
    fun cancelledSchedulingKeepsTheIntentAndCannotPublishALateAck() = runTest {
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
        val job = async { f.queue() }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, f.records.value.size)
        assertEquals(1, f.scheduledKeys.size)
    }

    @Test
    fun ownerOrCompanyLossDuringSchedulingDoesNotPublishTheOldAck() = runTest {
        for (logout in listOf(true, false)) {
            val f = MessageIntentFixture()
            f.schedule = {
                if (logout) f.setup.session = null
                else f.gate.value = CollaborationListAdmission("denied", f.denied)
                AppResult.Success(Unit)
            }
            assertIs<AppResult.Failed>(f.queue())
            assertEquals(1, f.records.value.size)
        }
    }

    @Test
    fun confirmedCompletionLookupDoesNotRequestTransportAgain() = runTest {
        val f = MessageIntentFixture()
        val record = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.queue()).value
        f.records.value =
            listOf(
                record.copy(
                    state =
                        CollaborationMessageState.Sent(
                            CollaborationMessageDevice(id(2), id(3), id(4), "!room:example.test"),
                            "tx",
                            "\$event",
                            CollaborationMessageOrigin("@self:example.test", "a".repeat(64)),
                        )
                )
            )
        val keys = f.scheduledKeys.toList()
        assertIs<AppResult.Success<CollaborationMessageRecord>>(f.queue())
        assertEquals(keys, f.scheduledKeys)
    }

    private fun id(value: Int) = "aaaaaaaa-0000-0000-0000-" + value.toString().padStart(12, '0')
}
