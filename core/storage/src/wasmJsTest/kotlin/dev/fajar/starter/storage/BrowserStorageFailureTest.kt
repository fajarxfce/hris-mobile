@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.storage

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import kotlin.js.JsException
import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class BrowserStorageFailureTest {
    @Test
    fun sdkPromiseAdaptersThatEraseNativeErrorTypesRetainTheirCause() = runTest {
        val original = Throwable("private SDK storage detail")
        var diagnostic: Exception? = null
        val result = safeStorageCall(onException = { diagnostic = it }) { throw original }
        assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(result).failure.kind)
        assertSame(original, assertIs<BrowserStorageException>(diagnostic).cause)
        assertFalse(result.failure.message.contains("private SDK"))
        val fatal = AssertionError("fatal Kotlin error")
        assertSame(fatal, assertFailsWith<AssertionError> { safeStorageCall { throw fatal } })
    }

    @Test
    fun nativeThrowsAndPromiseRejectionsUseTheSanitizedFailureBoundary() = runTest {
        val diagnostics = mutableListOf<Exception>()
        val thrown =
            safeStorageCall(onException = { diagnostics.add(it) }) { throwNativeStorageError() }
        val rejected =
            safeStorageCall(onException = { diagnostics.add(it) }) {
                rejectNativeStorage().await<JsAny?>()
            }
        for (result in listOf(thrown, rejected)) {
            assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(result).failure.kind)
            assertFalse(result.failure.message.contains("private SDK"))
        }
        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.all { it is BrowserStorageException && it.cause is JsException })
    }

    @Test
    fun malformedNativeRecordsCanRecoverOnTheNextStorageEmission() = runTest {
        val values =
            safeStorageFlow(
                    flowOf(1, 2),
                    onException = {},
                    mapValue = {
                        if (it == 1) throwNativeStorageError()
                        it
                    },
                )
                .toList()
        assertIs<AppResult.Failed>(values[0])
        assertEquals(AppResult.Success(2), values[1])
        val failedSource =
            safeStorageFlow(
                    flow<Int> {
                        emit(1)
                        throwNativeStorageError()
                    },
                    onException = {},
                )
                .toList()
        assertEquals(AppResult.Success(1), failedSource[0])
        assertIs<AppResult.Failed>(failedSource[1])
    }

    @Test
    fun downstreamNativeErrorsRemainTheCollectorsResponsibility() = runTest {
        var reports = 0
        assertFailsWith<JsException> {
            safeStorageFlow(flowOf(1), onException = { reports++ }).collect {
                throwNativeStorageError()
            }
        }
        assertEquals(0, reports)
    }
}

@JsFun("() => { throw new Error('private SDK storage detail'); }")
private external fun throwNativeStorageError()

@JsFun("() => Promise.reject(new Error('private SDK storage detail'))")
private external fun rejectNativeStorage(): Promise<JsAny?>
