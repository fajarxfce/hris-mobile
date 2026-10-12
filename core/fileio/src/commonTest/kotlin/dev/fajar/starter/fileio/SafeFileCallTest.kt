package dev.fajar.starter.fileio

import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.IOException

class SafeFileCallTest {
    @Test
    fun outputDismissalAndWriteFailuresHaveOperationSpecificMeaning() = runTest {
        assertEquals(AppResult.Success(false), safeFileWriteCall { false })
        val original = IOException("private provider detail")
        val diagnostics = mutableListOf<Exception>()
        val result = safeFileWriteCall(onException = diagnostics::add) { throw original }
        val failure = assertIs<AppResult.Failed>(result).failure
        assertEquals(FailureKind.Storage, failure.kind)
        assertEquals("client.file_write_failed", failure.code)
        assertFalse(failure.message.contains("private provider"))
        assertSame(original, diagnostics.single())
        assertEquals("client.file_read_failed", mapFileFailure(original).code)
        assertEquals("client.file_permission", mapFileWriteFailure(FilePermissionException()).code)
        assertEquals("client.file_busy", mapFileWriteFailure(FileBusyException()).code)
    }

    @Test
    fun outputCancellationAndFatalErrorsNeverBecomeAResult() = runTest {
        val cancelled = CancellationException("cancelled")
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> { safeFileWriteCall { throw cancelled } },
        )
        val fatal = AssertionError("invariant")
        assertSame(fatal, assertFailsWith<AssertionError> { safeFileWriteCall { throw fatal } })
    }

    @Test
    fun nullMeansDismissedAndTypedFailuresRetainTheirClassification() = runTest {
        assertEquals(AppResult.Success(null), safeFileCall { null })
        val original = FileBusyException()
        val diagnostics = mutableListOf<Exception>()
        val result = safeFileCall(onException = diagnostics::add) { throw original }
        val failure = assertIs<AppResult.Failed>(result).failure
        assertEquals(FailureKind.Conflict, failure.kind)
        assertEquals("client.file_busy", failure.code)
        assertSame(original, diagnostics.single())
        assertEquals(FailureKind.Permission, mapFileFailure(FilePermissionException()).kind)
        assertEquals(FailureKind.Unavailable, mapFileFailure(FileReleasedException()).kind)
        assertEquals(FailureKind.Conflict, mapFileFailure(FileChangedException()).kind)
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
                    throw FileUnavailableException()
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
