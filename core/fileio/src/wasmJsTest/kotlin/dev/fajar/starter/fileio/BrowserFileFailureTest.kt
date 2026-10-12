@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.fileio

import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class BrowserFileFailureTest {
    @Test
    fun browserPermissionAndStorageErrorsKeepTheirMeaningAndDiagnosticCause() = runTest {
        val cases =
            mapOf(
                "NotAllowedError" to (FailureKind.Permission to "client.file_permission"),
                "SecurityError" to (FailureKind.Permission to "client.file_permission"),
                "NotFoundError" to (FailureKind.Unavailable to "client.file_unavailable"),
                "InvalidStateError" to (FailureKind.Unavailable to "client.file_released"),
                "InvalidModificationError" to (FailureKind.Conflict to "client.file_changed"),
                "QuotaExceededError" to (FailureKind.Storage to "client.file_write_failed"),
            )
        for ((name, expected) in cases) {
            val diagnostics = mutableListOf<Exception>()
            val result =
                safeFileWriteCall(onException = diagnostics::add) { throwBrowserFileError(name) }
            val failure = assertIs<AppResult.Failed>(result).failure
            assertEquals(expected.first, failure.kind, name)
            assertEquals(expected.second, failure.code, name)
            assertFalse(failure.message.contains("private"))
            assertNotNull(diagnostics.single().cause)
        }
    }
}

@JsFun("(name) => { throw new DOMException('private provider detail', name); }")
private external fun throwBrowserFileError(name: String)
