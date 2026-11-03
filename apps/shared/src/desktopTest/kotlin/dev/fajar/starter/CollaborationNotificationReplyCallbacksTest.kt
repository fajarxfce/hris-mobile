package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CollaborationNotificationReplyCallbacksTest {
    private val token = CollaborationNotificationActionToken("v2.ZmljdGlvbmFs")
    private val reference =
        CollaborationNotificationInputReference(
            "q" + "a".repeat(64),
            "00000000-0000-4000-8000-000000000001",
        )

    @Test
    fun cancellationBeforeLaunchEntryStillFinishesExactlyOnce() = runTest {
        var entered = false
        val results = mutableListOf<CollaborationReplyCaptureOutcome>()
        val owner =
            CollaborationNotificationReplyCallbacks(
                { _, _ ->
                    entered = true
                    AppResult.Success(reference)
                },
                coroutineContext,
            )
        owner.receive(token, "Fictional reply", results::add)
        owner.closeAndJoin()
        assertFalse(entered)
        assertEquals(listOf(CollaborationReplyCaptureOutcome.NotSaved), results)
        owner.receive(token, "After close", results::add)
        assertEquals(2, results.size)
    }

    @Test
    fun eightPendingCallbacksRejectTheNinthAndJoinCaptureBeforeFinishing() = runTest {
        var entered = 0
        val joined = mutableSetOf<String>()
        val finished = mutableListOf<CollaborationReplyCaptureOutcome>()
        val owner =
            CollaborationNotificationReplyCallbacks(
                { _, text ->
                    entered++
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            delay(25)
                            joined += text
                        }
                    }
                },
                coroutineContext,
            )
        repeat(8) { index ->
            owner.receive(token, "Fictional $index") {
                assertTrue("Fictional $index" in joined)
                finished += it
            }
        }
        runCurrent()
        assertEquals(8, entered)
        val refused = mutableListOf<CollaborationReplyCaptureOutcome>()
        owner.receive(token, "Ninth", refused::add)
        assertEquals(listOf(CollaborationReplyCaptureOutcome.NotSaved), refused)
        // Each finish follows its own cleanup; all eight delays share the same virtual deadline.
        owner.closeAndJoin()
        assertEquals(8, finished.size)
        assertTrue(finished.all { it == CollaborationReplyCaptureOutcome.NotSaved })
    }

    @Test
    fun deadlineJoinsPendingCaptureAndReleasesAdmissionForLaterInput() = runTest {
        var joined = false
        var slow = true
        val results = mutableListOf<CollaborationReplyCaptureOutcome>()
        val owner =
            CollaborationNotificationReplyCallbacks(
                { _, _ ->
                    if (slow)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                delay(50)
                                joined = true
                            }
                        }
                    AppResult.Success(reference)
                },
                coroutineContext,
            )
        owner.receive(token, "First") {
            assertTrue(joined)
            results += it
        }
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue(results.isEmpty())
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(CollaborationReplyCaptureOutcome.NotSaved), results)
        slow = false
        owner.receive(token, "Second", results::add)
        runCurrent()
        assertEquals(CollaborationReplyCaptureOutcome.Saved, results.last())
        owner.closeAndJoin()
    }

    @Test
    fun failedOrThrowingCaptureDoesNotPublishSavedOrCancelItsSibling() = runTest {
        val results = mutableMapOf<String, CollaborationReplyCaptureOutcome>()
        val owner =
            CollaborationNotificationReplyCallbacks(
                { _, text ->
                    when (text) {
                        "failure" ->
                            AppResult.Failed(Failure(FailureKind.Storage, "Fictional failure"))
                        "throws" -> error("Fictional native error")
                        else -> {
                            delay(10)
                            AppResult.Success(reference)
                        }
                    }
                },
                coroutineContext,
            )
        for (text in listOf("failure", "throws", "success")) owner.receive(token, text) {
            results[text] = it
        }
        advanceTimeBy(10)
        runCurrent()
        assertEquals(CollaborationReplyCaptureOutcome.NotSaved, results["failure"])
        assertEquals(CollaborationReplyCaptureOutcome.NotSaved, results["throws"])
        assertEquals(CollaborationReplyCaptureOutcome.Saved, results["success"])
        owner.closeAndJoin()
    }

    @Test
    fun cancelledParentBeforeAdmissionDoesNotStartCapture() = runTest {
        val parent = Job().apply { cancel() }
        var entered = false
        val results = mutableListOf<CollaborationReplyCaptureOutcome>()
        val owner =
            CollaborationNotificationReplyCallbacks(
                { _, _ ->
                    entered = true
                    AppResult.Success(reference)
                },
                coroutineContext + parent,
            )
        owner.receive(token, "Fictional reply", results::add)
        owner.closeAndJoin()
        assertFalse(entered)
        assertEquals(listOf(CollaborationReplyCaptureOutcome.NotSaved), results)
    }
}
