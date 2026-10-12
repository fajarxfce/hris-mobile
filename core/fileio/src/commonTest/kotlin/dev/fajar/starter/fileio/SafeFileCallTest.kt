package dev.fajar.starter.fileio

import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SafeFileCallTest {
    @Test
    fun nullMeansDismissedAndTypedFailuresRetainTheirClassification() = runTest {
        assertEquals(AppResult.Success(null), safeFileCall { null })
        val original = FileInputBusyException()
        val diagnostics = mutableListOf<Exception>()
        val result = safeFileCall(onException = diagnostics::add) { throw original }
        val failure = assertIs<AppResult.Failed>(result).failure
        assertEquals(FailureKind.Conflict, failure.kind)
        assertEquals("client.file_busy", failure.code)
        assertSame(original, diagnostics.single())
        assertEquals(FailureKind.Permission, mapFileFailure(FileInputPermissionException()).kind)
        assertEquals(FailureKind.Unavailable, mapFileFailure(FileInputReleasedException()).kind)
        assertEquals(FailureKind.Conflict, mapFileFailure(FileInputChangedException()).kind)
    }

    @Test
    fun cancellationWinsOverALateNativeFailure() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var reported = false
        val operation = async {
            safeFileCall(onException = { reported = true }) {
                withContext(NonCancellable) {
                    started.complete(Unit)
                    finish.await()
                    throw FileInputUnavailableException()
                }
            }
        }
        started.await()
        operation.cancel()
        finish.complete(Unit)
        operation.join()
        assertTrue(operation.isCancelled)
        assertFalse(reported)
    }

    @Test
    fun cancellationAndFatalErrorsAreNotConvertedToFailures() = runTest {
        val cancelled = CancellationException("cancelled")
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> { safeFileCall { throw cancelled } },
        )
        val fatal = AssertionError("invariant")
        assertSame(fatal, assertFailsWith<AssertionError> { safeFileCall { throw fatal } })
    }
}
