package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.SdkChildResources
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SdkChildResourcesTest {
    @Test
    fun closingOneScreenDoesNotCloseItsSiblingAndParentClosesEveryRemainingChild() = runTest {
        val parent = SdkChildResources(backgroundScope)
        val first = Child()
        val second = Child()
        val a =
            assertIs<AppResult.Success<*>>(parent.open(first)).value
                as dev.fajar.starter.collaboration.data.repositories.SdkChildLease<*>
        assertIs<AppResult.Success<*>>(parent.open(second))
        assertEquals(AppResult.Success(Unit), a.close())
        assertEquals(1, first.released)
        assertEquals(0, second.released)
        assertEquals(AppResult.Success(Unit), parent.close())
        assertEquals(AppResult.Success(Unit), parent.close())
        assertEquals(1, first.released)
        assertEquals(1, second.released)
        val late = Child()
        assertIs<AppResult.Failed>(parent.open(late))
        assertEquals(0, late.initialized)
    }

    @Test
    fun cancelledScreenWaiterRetainsLatePartialAcquisitionUntilItIsDisposed() = runTest {
        val acquired = CompletableDeferred<Unit>()
        val child = Child().apply { initialize = { acquired.await() } }
        val parent = SdkChildResources(backgroundScope, 1)
        val waiter = async { parent.open(child) }
        runCurrent()
        waiter.cancel()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(waiter.isCompleted)
        assertEquals(0, child.released)
        assertIs<AppResult.Failed>(parent.open(Child()))
        acquired.complete(Unit)
        runCurrent()
        assertEquals(1, child.released)
        val next = Child()
        assertIs<AppResult.Success<*>>(parent.open(next))
        assertEquals(AppResult.Success(Unit), parent.close())
        assertEquals(1, next.released)
    }

    @Test
    fun parentCloseDuringAcquisitionNeverPublishesAnOpenChild() = runTest {
        val acquired = CompletableDeferred<Unit>()
        val child = Child().apply { initialize = { acquired.await() } }
        val parent = SdkChildResources(backgroundScope)
        val waiter = async { parent.open(child) }
        runCurrent()
        val closing = async { parent.close() }
        runCurrent()
        acquired.complete(Unit)
        assertIs<AppResult.Failed>(waiter.await())
        assertEquals(AppResult.Success(Unit), closing.await())
        assertEquals(1, child.released)
    }

    @Test
    fun failedInitializationStillReleasesAlreadyAcquiredResources() = runTest {
        val child = Child().apply { initialize = { error("After a partial native acquisition") } }
        val parent = SdkChildResources(backgroundScope)
        assertIs<AppResult.Failed>(parent.open(child))
        assertEquals(1, child.initialized)
        assertEquals(1, child.released)
        assertEquals(AppResult.Success(Unit), parent.close())
        assertEquals(1, child.released)
    }

    @Test
    fun interruptedInitializerStillHasAnOwnedCleanup() = runTest {
        val child =
            Child().apply { initialize = { throw CancellationException("Native cancellation") } }
        val parent = SdkChildResources(backgroundScope)
        assertIs<AppResult.Failed>(parent.open(child))
        assertEquals(1, child.released)
        assertEquals(AppResult.Success(Unit), parent.close())
    }

    @Test
    fun failedCleanupRetainsCapacityAndParentOwnershipUntilExplicitRetry() = runTest {
        val child =
            Child().apply {
                initialize = { error("Partial acquisition") }
                release = { error("Still active") }
            }
        val parent = SdkChildResources(backgroundScope, 1)
        assertIs<AppResult.Failed>(parent.open(child))
        assertIs<AppResult.Failed>(parent.open(Child()))
        assertIs<AppResult.Failed>(parent.close())
        assertEquals(0, child.released)
        assertEquals(2, child.releaseAttempts)
        child.release = {}
        assertEquals(AppResult.Success(Unit), parent.close())
        assertEquals(1, child.released)
        assertEquals(3, child.releaseAttempts)
    }

    @Test
    fun waitDeadlineDoesNotCancelInitializationAndItsLateResultIsNotAbandoned() = runTest {
        val acquired = CompletableDeferred<Unit>()
        val child = Child().apply { initialize = { acquired.await() } }
        val parent = SdkChildResources(backgroundScope)
        val waiter = async { parent.open(child) }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertIs<AppResult.Failed>(waiter.await())
        assertEquals(0, child.released)
        acquired.complete(Unit)
        runCurrent()
        assertEquals(1, child.released)
        assertEquals(AppResult.Success(Unit), parent.close())
    }

    @Test
    fun concurrentScreenAndParentDisposalShareTheSameNativeTermination() = runTest {
        val finish = CompletableDeferred<Unit>()
        val child = Child().apply { release = { finish.await() } }
        val parent = SdkChildResources(backgroundScope)
        val lease =
            assertIs<AppResult.Success<*>>(parent.open(child)).value
                as dev.fajar.starter.collaboration.data.repositories.SdkChildLease<*>
        val screen = async { lease.close() }
        val app = async { parent.close() }
        runCurrent()
        assertEquals(1, child.releaseAttempts)
        screen.cancel()
        advanceTimeBy(5_000)
        runCurrent()
        assertIs<AppResult.Failed>(app.await())
        finish.complete(Unit)
        runCurrent()
        assertEquals(1, child.released)
        assertEquals(AppResult.Success(Unit), parent.close())
        assertEquals(1, child.releaseAttempts)
    }
}

private suspend fun SdkChildResources.open(child: Child) =
    acquire(
        child,
        {
            it.initialized++
            it.initialize()
        },
        {
            it.releaseAttempts++
            it.release()
            it.released++
        },
    )

private class Child {
    var initialized = 0
    var released = 0
    var releaseAttempts = 0
    var initialize: suspend () -> Unit = {}
    var release: suspend () -> Unit = {}
}
