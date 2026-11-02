package dev.fajar.starter.worker

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollapsedWorkGateTest {
    @Test
    fun futureWorkIsReusedButARunningWorkerGetsOneSuccessor() = runTest {
        val gate = CollapsedWorkGate(setOf("task"))
        var state = WorkQueueSnapshot(setOf(0), false)
        var appends = 0
        repeat(40) {
            gate.enqueue("task", { state }) {
                assertEquals(1, it)
                appends++
                state = WorkQueueSnapshot(setOf(0, 1), true)
            }
        }
        assertEquals(1, appends)
        state = WorkQueueSnapshot(setOf(0), true)
        gate.enqueue("task", { state }) { error("Backoff is already pending") }
    }

    @Test
    fun concurrentRequestsHaveBoundedAdmissionAndOneDurableAppend() = runTest {
        val gate = CollapsedWorkGate(setOf("task"))
        val release = CompletableDeferred<Unit>()
        var state = WorkQueueSnapshot(emptySet(), false)
        var queries = 0
        var appends = 0
        val calls =
            (1..40).map {
                async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        gate.enqueue(
                            "task",
                            {
                                queries++
                                release.await()
                                state
                            },
                        ) {
                            appends++
                            state = WorkQueueSnapshot(setOf(0), true)
                        }
                        true
                    } catch (_: IllegalStateException) {
                        false
                    }
                }
            }
        assertEquals(1, queries)
        assertEquals(8, calls.count { it.isCompleted })
        release.complete(Unit)
        assertEquals(32, calls.awaitAll().count { it })
        assertEquals(32, queries)
        assertEquals(1, appends)
    }

    @Test
    fun unrelatedKeysDoNotWaitForTheOtherKeysDatabaseOperation() = runTest {
        val gate = CollapsedWorkGate(setOf("a", "b"), maxCallers = 2)
        val release = CompletableDeferred<Unit>()
        val first =
            async(start = CoroutineStart.UNDISPATCHED) {
                gate.enqueue(
                    "a",
                    {
                        release.await()
                        WorkQueueSnapshot(emptySet(), false)
                    },
                ) {}
            }
        var second = false
        gate.enqueue("b", { WorkQueueSnapshot(emptySet(), false) }) { second = true }
        assertTrue(second)
        assertFalse(first.isCompleted)
        release.complete(Unit)
        first.await()
    }

    @Test
    fun cancellationWhileWaitingReleasesAdmissionWithoutQueryingOrAppending() = runTest {
        val gate = CollapsedWorkGate(setOf("task"), maxCallers = 2)
        val release = CompletableDeferred<Unit>()
        val holder =
            launch(start = CoroutineStart.UNDISPATCHED) {
                gate.enqueue(
                    "task",
                    {
                        release.await()
                        WorkQueueSnapshot(setOf(0), true)
                    },
                ) {
                    error("Already pending")
                }
            }
        val cancelled =
            launch(start = CoroutineStart.UNDISPATCHED) {
                gate.enqueue("task", { error("Cancelled query") }) { error("Cancelled append") }
            }
        cancelled.cancelAndJoin()
        val replacement =
            async(start = CoroutineStart.UNDISPATCHED) {
                gate.enqueue("task", { WorkQueueSnapshot(setOf(0), true) }) {
                    error("Already pending")
                }
            }
        assertFalse(replacement.isCompleted)
        release.complete(Unit)
        holder.join()
        replacement.await()
        gate.enqueue("task", { WorkQueueSnapshot(setOf(0), true) }) { error("No slot leaked") }
    }

    @Test
    fun queryAndAppendFailuresReleaseTheOwnerForALaterRepair() = runTest {
        val gate = CollapsedWorkGate(setOf("task"), maxCallers = 1)
        assertFailsWith<IllegalStateException> {
            gate.enqueue("task", { error("Database unavailable") }) { error("No append") }
        }
        assertFailsWith<IllegalStateException> {
            gate.enqueue("task", { WorkQueueSnapshot(emptySet(), false) }) {
                error("Enqueue unavailable")
            }
        }
        var repaired = false
        gate.enqueue("task", { WorkQueueSnapshot(emptySet(), false) }) { repaired = true }
        assertTrue(repaired)
    }

    @Test
    fun cancellationAfterACommittedAppendPropagatesWithoutAssumingRollback() = runTest {
        val gate = CollapsedWorkGate(setOf("task"), maxCallers = 1)
        var state = WorkQueueSnapshot(emptySet(), false)
        val job = launch {
            gate.enqueue("task", { state }) {
                state = WorkQueueSnapshot(setOf(0), true)
                currentCoroutineContext().cancel()
            }
            error("Cancelled append result")
        }
        job.join()
        assertTrue(job.isCancelled)
        gate.enqueue("task", { state }) { error("Committed request already exists") }
    }

    @Test
    fun twoOccupiedSlotsCannotAdmitAThirdRequest() = runTest {
        val gate = CollapsedWorkGate(setOf("task"))
        assertFailsWith<IllegalStateException> {
            gate.enqueue("task", { WorkQueueSnapshot(setOf(0, 1), false) }) {
                error("Third unfinished request")
            }
        }
        gate.enqueue("task", { WorkQueueSnapshot(setOf(0, 1), true) }) {
            error("A future start exists")
        }
        var appended = false
        gate.enqueue("task", { WorkQueueSnapshot(setOf(0), false) }) { appended = true }
        assertTrue(appended)
    }

    @Test
    fun invalidRegistrationsAndAlreadyCancelledCallersCannotReachTheQueue() = runTest {
        for (keys in
            listOf(
                setOf(""),
                setOf("private/value"),
                setOf("x".repeat(129)),
                (1..33).map { "task$it" }.toSet(),
            )) {
            assertFailsWith<IllegalArgumentException> { CollapsedWorkGate(keys) }
        }
        for (capacity in listOf(0, 33)) {
            assertFailsWith<IllegalArgumentException> { CollapsedWorkGate(setOf("task"), capacity) }
        }
        val gate = CollapsedWorkGate(setOf("task"))
        assertTrue(gate.contains("task"))
        assertFalse(gate.contains("unknown"))
        val job = launch {
            currentCoroutineContext().cancel()
            gate.enqueue("task", { error("No cancelled query") }) { error("No cancelled append") }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFailsWith<NoSuchElementException> {
            gate.enqueue("unknown", { error("No unknown query") }) { error("No unknown append") }
        }
    }
}
