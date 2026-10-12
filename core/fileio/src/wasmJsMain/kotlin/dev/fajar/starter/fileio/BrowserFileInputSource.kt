@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.fileio

import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The page/container owns this source; each picker and FileReader has explicit cancellation. */
class BrowserFileInputSource : FileInputSource {
    private val inputs = FileHandleRegistry<BrowserInputFile>()

    override suspend fun select(requestId: String, mediaTypes: Set<String>): FileInputMetadata? =
        coroutineScope {
            validateFileMediaTypes(mediaTypes)
            val ticket = inputs.reserve(requestId, currentCoroutineContext().job)
            var retained = false
            var picker: BrowserFilePicker? = null
            try {
                picker = createBrowserFilePicker(mediaTypes.joinToString(","))
                val file = picker.result.await<BrowserInputFile?>() ?: return@coroutineScope null
                require(
                    file.size.isFinite() && file.size >= 0 && file.size <= 9_007_199_254_740_991.0
                )
                val metadata =
                    FileInputMetadata(
                        file.name,
                        file.type.takeIf { it.isNotBlank() },
                        file.size.toLong(),
                        file.lastModified.toLong().takeIf { it > 0 },
                    )
                inputs.publish(requestId, ticket, file)
                retained = true
                metadata
            } finally {
                picker?.cancel()
                if (!retained) inputs.discard(requestId, ticket)
            }
        }

    override fun read(requestId: String): Flow<ByteArray> =
        flow {
                coroutineScope {
                    val operation = currentCoroutineContext().job
                    val file = inputs.beginOperation(requestId, operation)
                    try {
                        var offset = 0L
                        val size = file.size.toLong()
                        while (offset < size) {
                            ensureActive()
                            val until = minOf(size, offset + FILE_INPUT_BLOCK_BYTES)
                            val reader =
                                readBrowserFileRange(file, offset.toDouble(), until.toDouble())
                            val bytes =
                                try {
                                    Base64.decode(reader.result.await<JsString>().toString())
                                } finally {
                                    reader.cancel()
                                }
                            if (bytes.size.toLong() != until - offset) throw FileChangedException()
                            emit(bytes)
                            offset = until
                        }
                    } finally {
                        inputs.endOperation(requestId, operation)
                    }
                }
            }
            .buffer(0)

    override suspend fun release(requestId: String) {
        inputs.release(requestId)
    }

    override fun close() {
        inputs.close()
    }
}

private external interface BrowserInputFile : JsAny {
    val name: String
    val type: String
    val size: Double
    val lastModified: Double
}

private external interface BrowserFilePicker : JsAny {
    val result: Promise<BrowserInputFile?>

    fun cancel()
}

private external interface BrowserFileRange : JsAny {
    val result: Promise<JsString>

    fun cancel()
}

@JsFun(
    """(accept) => {
    const input = document.createElement('input');
    input.type = 'file'; input.accept = accept; input.multiple = false;
    input.hidden = true; input.dataset.appFilePicker = 'true';
    let settled = false, finish;
    const result = new Promise((resolve, reject) => {
        finish = (file, error) => {
            if (settled) return;
            settled = true;
            input.onchange = null; input.oncancel = null; input.remove();
            if (error) reject(error); else resolve(file);
        };
        input.onchange = () => finish(input.files?.[0] ?? null);
        input.oncancel = () => finish(null);
        document.body.appendChild(input);
        try { input.click(); } catch (error) { finish(null, error); }
    });
    return {result, cancel: () => finish(null)};
}"""
)
private external fun createBrowserFilePicker(accept: String): BrowserFilePicker

// FileReader serializes just this bounded slice. It never reads the whole file into a data URL.
@JsFun(
    """(file, offset, until) => {
    const reader = new FileReader();
    let settled = false, finish;
    const result = new Promise((resolve, reject) => {
        finish = (value, error) => {
            if (settled) return;
            settled = true;
            reader.onload = null; reader.onerror = null; reader.onabort = null;
            if (error) reject(error); else resolve(value);
        };
        reader.onload = () => {
            const value = reader.result;
            if (typeof value !== 'string' || !value.startsWith('data:') || !value.includes(';base64,')) {
                finish(null, new Error('Invalid file-reader result.')); return;
            }
            finish(value.substring(value.indexOf(';base64,') + 8));
        };
        reader.onerror = () => finish(null, reader.error);
        reader.onabort = () => finish(null, new DOMException('File read cancelled.', 'AbortError'));
        try { reader.readAsDataURL(file.slice(offset, until)); }
        catch (error) { finish(null, error); }
    });
    return {result, cancel: () => {
        if (!settled) {
            finish(null, new DOMException('File read cancelled.', 'AbortError'));
            if (reader.readyState === FileReader.LOADING) reader.abort();
        }
    }};
}"""
)
private external fun readBrowserFileRange(
    file: BrowserInputFile,
    offset: Double,
    until: Double,
): BrowserFileRange
