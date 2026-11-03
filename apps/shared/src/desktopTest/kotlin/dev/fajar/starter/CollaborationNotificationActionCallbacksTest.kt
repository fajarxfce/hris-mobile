package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CollaborationNotificationActionCallbacksTest {
    private val input =
        ActionNotificationInput(
            ActionNotificationReference("fictional", "render"),
            ActionNotificationKind.Dismiss,
            "v2.ZmljdGlvbmFs",
        )

    @Test
    fun openReplyAndUnexpectedTextCannotEnterTheActionHandler() = runTest {
        val outcomes = mutableListOf<CollaborationActionCallbackOutcome>()
        val owner =
            CollaborationNotificationActionCallbacks(
                { error("Unexpected handler") },
                coroutineContext,
            )
        for (bad in
            listOf(
                input.copy(kind = ActionNotificationKind.Open),
                input.copy(kind = ActionNotificationKind.Reply),
                input.copy(replyText = "Fictional text"),
            )) owner.receive(bad, outcomes::add)
        runCurrent()
        assertEquals(List(3) { CollaborationActionCallbackOutcome.NotConfirmed }, outcomes)
        owner.closeAndJoin()
    }

    @Test
    fun onlyMatchingCompletedAndObsoleteResultsAreAcknowledged() = runTest {
        val results =
            listOf(
                AppResult.Success(
                    CollaborationNotificationActionOutcome.Completed(ActionNotificationKind.Dismiss)
                ),
                AppResult.Success(
                    CollaborationNotificationActionOutcome.Completed(
                        ActionNotificationKind.MarkRead
                    )
                ),
                AppResult.Success(CollaborationNotificationActionOutcome.Obsolete),
                AppResult.Failed(Failure(FailureKind.Storage, "Unavailable")),
            )
        val expected =
            listOf(
                CollaborationActionCallbackOutcome.Applied,
                CollaborationActionCallbackOutcome.NotConfirmed,
                CollaborationActionCallbackOutcome.Obsolete,
                CollaborationActionCallbackOutcome.NotConfirmed,
            )
        for ((result, outcome) in results.zip(expected)) {
            val outcomes = mutableListOf<CollaborationActionCallbackOutcome>()
            val owner = CollaborationNotificationActionCallbacks({ result }, coroutineContext)
            owner.receive(input, outcomes::add)
            runCurrent()
            assertEquals(listOf(outcome), outcomes)
            owner.closeAndJoin()
        }
    }

    @Test
    fun cancelledLaunchAndClosedOwnerFinishOnceWithoutStartingAnAction() = runTest {
        val outcomes = mutableListOf<CollaborationActionCallbackOutcome>()
        val owner =
            CollaborationNotificationActionCallbacks(
                { error("Unexpected handler") },
                coroutineContext,
            )
        owner.receive(input, outcomes::add)
        owner.closeAndJoin()
        assertEquals(listOf(CollaborationActionCallbackOutcome.NotConfirmed), outcomes)
        owner.receive(input, outcomes::add)
        assertEquals(2, outcomes.size)
    }

    @Test
    fun eightPendingActionsBoundAdmissionAndJoinCleanupBeforeFinishing() = runTest {
        var entered = 0
        var joined = 0
        val outcomes = mutableListOf<CollaborationActionCallbackOutcome>()
        val owner =
            CollaborationNotificationActionCallbacks(
                {
                    entered++
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            delay(25)
                            joined++
                        }
                    }
                },
                coroutineContext,
            )
        repeat(8) { owner.receive(input, outcomes::add) }
        runCurrent()
        owner.receive(input, outcomes::add)
        assertEquals(8, entered)
        assertEquals(listOf(CollaborationActionCallbackOutcome.NotConfirmed), outcomes)
        owner.closeAndJoin()
        assertEquals(8, joined)
        assertEquals(List(9) { CollaborationActionCallbackOutcome.NotConfirmed }, outcomes)
    }

    @Test
    fun timeoutJoinsAndAThrowingSiblingDoesNotPreventALaterAction() = runTest {
        var call = 0
        var joined = false
        val outcomes = mutableListOf<CollaborationActionCallbackOutcome>()
        val owner =
            CollaborationNotificationActionCallbacks(
                {
                    when (call++) {
                        0 ->
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) {
                                    delay(50)
                                    joined = true
                                }
                            }
                        1 -> error("Simulated private exception")
                        else ->
                            AppResult.Success(
                                CollaborationNotificationActionOutcome.Completed(input.kind)
                            )
                    }
                },
                coroutineContext,
            )
        owner.receive(input) {
            assertTrue(joined)
            outcomes += it
        }
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue(outcomes.isEmpty())
        advanceTimeBy(50)
        runCurrent()
        repeat(2) { owner.receive(input, outcomes::add) }
        runCurrent()
        assertEquals(
            listOf(
                CollaborationActionCallbackOutcome.NotConfirmed,
                CollaborationActionCallbackOutcome.NotConfirmed,
                CollaborationActionCallbackOutcome.Applied,
            ),
            outcomes,
        )
        owner.closeAndJoin()
    }
}
