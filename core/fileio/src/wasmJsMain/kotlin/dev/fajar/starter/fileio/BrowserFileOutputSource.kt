@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.fileio

import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** Streaming save through File System Access. Unsupported hosts report a capability failure. */
class BrowserFileOutputSource : FileOutputSource {
    private val outputs = FileHandleRegistry<BrowserOutput>()

    override suspend fun select(
        requestId: String,
        fileName: String,
        mediaType: String,
        size: Long,
    ): Boolean = coroutineScope {
        validateFileOutput(fileName, mediaType, size)
        require(size <= 9_007_199_254_740_991L)
        if (!browserFileSaveAvailable()) throw FileUnavailableException()
        val ticket = outputs.reserve(requestId, currentCoroutineContext().job)
        var picker: BrowserOutputPicker? = null
        var retained = false
        try {
            picker = createBrowserOutputPicker(fileName, size.toDouble())
            val file = picker.result.await<BrowserOutputFile?>() ?: return@coroutineScope false
            outputs.publish(requestId, ticket, BrowserOutput(ticket, file))
            retained = true
            true
        } finally {
            if (!retained) {
                picker?.cancel()
                outputs.discard(requestId, ticket)?.file?.let(::requestBrowserOutputAbort)
            }
        }
    }

    override suspend fun write(requestId: String, offset: Long, bytes: ByteArray) = coroutineScope {
        val operation = currentCoroutineContext().job
        val output = outputs.beginOperation(requestId, operation)
        try {
            require(bytes.size in 1..FILE_OUTPUT_BLOCK_BYTES && offset in 0..9_007_199_254_740_991L)
            output.file.write(offset.toDouble(), Base64.encode(bytes)).await<JsAny?>()
        } catch (cause: Throwable) {
            outputs.discard(requestId, output.ticket)
            abortBrowserOutputAfterFailure(output.file, cause)
            throw cause
        } finally {
            outputs.endOperation(requestId, operation)
        }
        Unit
    }

    override suspend fun complete(requestId: String) = coroutineScope {
        val operation = currentCoroutineContext().job
        val output = outputs.beginOperation(requestId, operation)
        try {
            output.file.complete().await<JsAny?>()
        } catch (cause: Throwable) {
            abortBrowserOutputAfterFailure(output.file, cause)
            throw cause
        } finally {
            outputs.discard(requestId, output.ticket)
        }
        Unit
    }

    override suspend fun release(requestId: String) {
        val output = outputs.release(requestId) ?: return
        output.file.abort().await<JsAny?>()
    }

    override fun close() {
        outputs.close().forEach { requestBrowserOutputAbort(it.file) }
    }
}

private data class BrowserOutput(val ticket: Any, val file: BrowserOutputFile)

private suspend fun abortBrowserOutputAfterFailure(file: BrowserOutputFile, cause: Throwable) {
    withContext(NonCancellable) {
        try {
            withTimeout(5.seconds) { file.abort().await<JsAny?>() }
        } catch (cleanup: Throwable) {
            cause.addSuppressed(cleanup)
        }
    }
}

private external interface BrowserOutputPicker : JsAny {
    val result: Promise<BrowserOutputFile?>

    fun cancel()
}

private external interface BrowserOutputFile : JsAny {
    fun write(offset: Double, encoded: String): Promise<JsAny?>

    fun complete(): Promise<JsAny?>

    fun abort(): Promise<JsAny?>
}

@JsFun(
    "() => typeof globalThis.showSaveFilePicker === 'function' && globalThis.isSecureContext === true"
)
private external fun browserFileSaveAvailable(): Boolean

@JsFun(
    """(file) => { file.abort().catch(() => console.warn('[fileio] File output cleanup failed.')); }"""
)
private external fun requestBrowserOutputAbort(file: BrowserOutputFile)

@JsFun(
    """(name, size) => {
    let cancelled = false, output = null;
    const result = (async () => {
        try {
            const handle = await globalThis.showSaveFilePicker({suggestedName: name});
            if (cancelled) return null;
            const stream = await handle.createWritable({keepExistingData: false});
            let closed = false, written = 0;
            output = {
                write: async (offset, encoded) => {
                    if (closed) throw new DOMException('Output released.', 'InvalidStateError');
                    const decoded = atob(encoded);
                    const bytes = Uint8Array.from(decoded, character => character.charCodeAt(0));
                    try {
                        if (offset !== written || bytes.length < 1 || bytes.length > 1048576 || bytes.length > size - written)
                            throw new DOMException('Output extent changed.', 'InvalidModificationError');
                        await stream.write({type: 'write', position: offset, data: bytes});
                        written += bytes.length;
                    } finally { bytes.fill(0); }
                },
                complete: async () => {
                    if (closed) throw new DOMException('Output released.', 'InvalidStateError');
                    if (written !== size) throw new DOMException('Output is incomplete.', 'InvalidModificationError');
                    await stream.close();
                    closed = true;
                },
                abort: () => {
                    if (closed) return Promise.resolve();
                    closed = true;
                    return stream.abort();
                },
            };
            if (cancelled) { await output.abort(); return null; }
            return output;
        } catch (error) {
            if (cancelled || error?.name === 'AbortError') return null;
            throw error;
        }
    })();
    return {result, cancel: () => {
        cancelled = true;
        if (output) output.abort().catch(() => console.warn('[fileio] File output cleanup failed.'));
    }};
}"""
)
private external fun createBrowserOutputPicker(name: String, size: Double): BrowserOutputPicker
