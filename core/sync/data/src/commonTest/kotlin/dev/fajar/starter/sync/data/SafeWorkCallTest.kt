package dev.fajar.starter.sync.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.data.errors.safeWorkCall
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SafeWorkCallTest {
    @Test
    fun rejectedSchedulingRetainsInternalDiagnostics() = runTest {
        val rejection = IllegalStateException("Internal scheduler detail")
        var diagnostic: Exception? = null
        val result = safeWorkCall(onException = { diagnostic = it }) { throw rejection }
        assertSame(rejection, diagnostic)
        val failure = assertIs<AppResult.Failed>(result).failure
        assertEquals(FailureKind.Unavailable, failure.kind)
        assertFalse(failure.message.contains("Internal scheduler detail"))
    }

    @Test
    fun cancelledOwnerDoesNotEnqueueMoreWork() = runTest {
        var scheduled = false
        val owner =
            launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                assertFailsWith<CancellationException> { safeWorkCall { scheduled = true } }
            }
        owner.join()
        assertFalse(scheduled)
    }
}
