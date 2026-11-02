package dev.fajar.starter

import dev.fajar.starter.app.work.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ApplicationPushReceiverTest {
    private val fields = mapOf("eventCode" to "collaboration.chat_available")
    private val unavailable =
        Failure(FailureKind.Network, "Fictional offline", code = "test.offline")

    @Test
    fun receiptOnlyNeverRunsAWorkerAndUnknownEventsNeverEnterAFeature() = runTest {
        var queued = 0
        val receive =
            ApplicationPushReceiver(
                { error("Wrong feature") },
                {
                    queued++
                    AppResult.Success(true)
                },
                { error("No worker") },
            )
        assertTrue(receive.receive(fields, process = false))
        assertFalse(receive.receive(mapOf("eventCode" to "unknown"), process = true))
        assertEquals(1, queued)
    }

    @Test
    fun rejectionAndFailureNeverStartProcessing() = runTest {
        for (result in listOf(AppResult.Success(false), AppResult.Failed(unavailable))) {
            val receive =
                ApplicationPushReceiver(
                    { error("Wrong feature") },
                    { result },
                    { error("No worker") },
                )
            assertFalse(receive.receive(fields, process = true))
        }
    }

    @Test
    fun acceptedTargetsRunOnlyTheirOwnWorkerAndPreserveProgressMeaning() = runTest {
        for (target in ApplicationPush.entries) {
            for (result in
                listOf(
                    SyncResult.Complete,
                    SyncResult.Retry(),
                    SyncResult.Retry(unavailable),
                    SyncResult.Blocked(unavailable),
                )) {
                val calls = mutableListOf<ApplicationPush>()
                val receive =
                    ApplicationPushReceiver(
                        { AppResult.Success(true) },
                        { AppResult.Success(true) },
                        {
                            calls += it
                            result
                        },
                    )
                val event =
                    if (target == ApplicationPush.Chat) fields
                    else mapOf("eventCode" to "communications.inbox_available")
                assertEquals(
                    result == SyncResult.Complete || result == SyncResult.Retry(),
                    receive.receive(event, process = true),
                )
                assertEquals(listOf(target), calls)
            }
        }
    }

    @Test
    fun parentCancellationStopsAndJoinsActiveProcessingWithoutPublishing() = runTest {
        val entered = CompletableDeferred<Unit>()
        var released = false
        val receive =
            ApplicationPushReceiver(
                { error("Wrong feature") },
                { AppResult.Success(true) },
                {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        released = true
                    }
                },
            )
        val job = launch {
            receive.receive(fields, true)
            error("Cancelled result")
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(released)
    }

    @Test
    fun expirationStopsWorkWithinTheAppleCallbackBudget() = runTest {
        var released = false
        val receive =
            ApplicationPushReceiver(
                { error("Wrong feature") },
                { AppResult.Success(true) },
                {
                    try {
                        awaitCancellation()
                    } finally {
                        released = true
                    }
                },
            )
        assertFalse(receive.receive(fields, true))
        assertEquals(25_000L, testScheduler.currentTime)
        assertTrue(released)
    }

    @Test
    fun cancelledQueueOrWorkerResultsCannotPublishSuccess() = runTest {
        for (cancelQueue in listOf(false, true)) {
            var runs = 0
            val receive =
                ApplicationPushReceiver(
                    { error("Wrong feature") },
                    {
                        if (cancelQueue) currentCoroutineContext().cancel()
                        AppResult.Success(true)
                    },
                    {
                        runs++
                        currentCoroutineContext().cancel()
                        SyncResult.Complete
                    },
                )
            val job = launch {
                receive.receive(fields, true)
                error("Cancelled result")
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(if (cancelQueue) 0 else 1, runs)
        }
    }
}
