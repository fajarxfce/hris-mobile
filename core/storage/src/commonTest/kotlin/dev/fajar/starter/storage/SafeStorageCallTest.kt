package dev.fajar.starter.storage

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class SafeStorageCallTest {
    @Test
    fun fatalKotlinErrorsAreNotConvertedToRecoverableStorageFailures() = runTest {
        val fatal = AssertionError("Broken application invariant")
        assertSame(fatal, assertFailsWith<AssertionError> { safeStorageCall { throw fatal } })
    }

    @Test
    fun cancellationSuppressesALateSdkFailureAndItsDiagnostics() = runTest {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var reports = 0
        val operation = launch {
            safeStorageCall(onException = { reports++ }) {
                started.complete(Unit)
                withContext(NonCancellable) {
                    released.await()
                    error("Late private SDK failure")
                }
            }
        }
        started.await()
        operation.cancel()
        released.complete(Unit)
        operation.join()
        assertEquals(0, reports)
    }

    @Test
    fun falseAndNullAreValidRawValues() = runTest {
        assertEquals(AppResult.Success(false), safeStorageCall { false })
        assertEquals(AppResult.Success(null), safeStorageCall { null })
    }

    @Test
    fun mapsTechnicalExceptionWithoutExposingItsMessage() = runTest {
        val result = safeStorageCall { error("private storage details") }
        assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(result).failure.kind)
        assertFalse(result.failure.message.contains("private storage details"))
    }

    @Test
    fun cancellationRemainsCancellation() = runTest {
        assertFailsWith<CancellationException> {
            safeStorageCall { throw CancellationException("disposed") }
        }
    }
}
