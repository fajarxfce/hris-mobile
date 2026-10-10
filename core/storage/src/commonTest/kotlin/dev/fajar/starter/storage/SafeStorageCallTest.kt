package dev.fajar.starter.storage

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class SafeStorageCallTest {
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
