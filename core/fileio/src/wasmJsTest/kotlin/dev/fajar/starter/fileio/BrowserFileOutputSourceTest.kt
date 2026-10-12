@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.fileio

import dev.fajar.starter.common.result.*
import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class BrowserFileOutputSourceTest {
    @Test
    fun boundedWritesCommitOnlyAfterTheDeclaredExtent() = runTest {
        val native = installOutputPicker()
        val source = BrowserFileOutputSource()
        try {
            val selection =
                async(start = CoroutineStart.UNDISPATCHED) {
                    source.select(
                        "one",
                        "report.pdf",
                        "application/pdf",
                        FILE_OUTPUT_BLOCK_BYTES + 3L,
                    )
                }
            native.choose()
            assertTrue(selection.await())
            val bytes = ByteArray(FILE_OUTPUT_BLOCK_BYTES) { (it % 251).toByte() }
            source.write("one", 0, bytes)
            source.write("one", bytes.size.toLong(), byteArrayOf(1, 2, 3))
            assertEquals(0, native.committed)
            assertEquals(FILE_OUTPUT_BLOCK_BYTES, native.maximumWrite)
            assertEquals(FILE_OUTPUT_BLOCK_BYTES + 3, native.written)
            assertTrue(native.buffersCleared())
            assertEquals(250.toByte(), bytes[250], "The caller's buffer remains borrowed")
            source.complete("one")
            assertEquals(1, native.committed)
            source.release("one")
            assertEquals(0, native.aborted)
            assertFailsWith<FileReleasedException> { source.write("one", 0, byteArrayOf(1)) }
        } finally {
            source.close()
            native.restore()
        }
    }

    @Test
    fun incompleteOutputIsAbortedAndCanNeverReportCompletion() = runTest {
        val native = installOutputPicker()
        val source = BrowserFileOutputSource()
        try {
            val selection =
                async(start = CoroutineStart.UNDISPATCHED) {
                    source.select("one", "report.pdf", "application/pdf", 3)
                }
            native.choose()
            selection.await()
            source.write("one", 0, byteArrayOf(1))
            val diagnostics = mutableListOf<Exception>()
            val result =
                safeFileWriteCall(onException = diagnostics::add) { source.complete("one") }
            assertEquals("client.file_changed", assertIs<AppResult.Failed>(result).failure.code)
            assertEquals(1, diagnostics.size)
            assertEquals(1, native.aborted)
            assertEquals(0, native.committed)
            assertFailsWith<FileReleasedException> { source.complete("one") }
        } finally {
            source.close()
            native.restore()
        }
    }

    @Test
    fun cancellationDuringNativeAcquisitionAbortsALateWriter() = runTest {
        val native = installOutputPicker(holdOpen = true)
        val source = BrowserFileOutputSource()
        try {
            val selection =
                async(start = CoroutineStart.UNDISPATCHED) {
                    source.select("one", "report.pdf", "application/pdf", 3)
                }
            native.choose()
            native.openStarted.await<JsAny?>()
            selection.cancelAndJoin()
            native.releaseOpen()
            native.abortObserved.await<JsAny?>()
            assertEquals(1, native.aborted)
            assertEquals(0, native.written)
            assertFailsWith<FileReleasedException> { source.write("one", 0, byteArrayOf(1)) }
        } finally {
            source.close()
            native.restore()
        }
    }

    @Test
    fun closingAPendingWriteCancelsItsOwnerAndClearsTheBridgeBuffer() = runTest {
        val native = installOutputPicker(holdWrite = true)
        val source = BrowserFileOutputSource()
        try {
            val selection =
                async(start = CoroutineStart.UNDISPATCHED) {
                    source.select("one", "report.pdf", "application/pdf", 3)
                }
            native.choose()
            selection.await()
            val writer = async { source.write("one", 0, byteArrayOf(1, 2, 3)) }
            native.writeStarted.await<JsAny?>()
            source.close()
            writer.join()
            assertTrue(writer.isCancelled)
            native.abortObserved.await<JsAny?>()
            native.writeSettled.await<JsAny?>()
            assertTrue(native.buffersCleared())
            assertEquals(1, native.aborted)
            assertEquals(0, native.committed)
        } finally {
            source.close()
            native.restore()
        }
    }

    @Test
    fun cancelledPickerDoesNotCreateAWriterAndDismissalIsNotFailure() = runTest {
        val native = installOutputPicker()
        val source = BrowserFileOutputSource()
        try {
            val cancelled =
                async(start = CoroutineStart.UNDISPATCHED) {
                    source.select("one", "report.pdf", "application/pdf", 3)
                }
            cancelled.cancelAndJoin()
            native.choose()
            native.pickerSettled.await<JsAny?>()
            assertEquals(0, native.opened)
            val dismissed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    safeFileWriteCall { source.select("one", "report.pdf", "application/pdf", 3) }
                }
            native.dismiss()
            assertEquals(AppResult.Success(false), dismissed.await())
        } finally {
            source.close()
            native.restore()
        }
    }

    @Test
    fun oversizedWritesAreRejectedBeforeReachingTheNativeStream() = runTest {
        val native = installOutputPicker()
        val source = BrowserFileOutputSource()
        try {
            val selection =
                async(start = CoroutineStart.UNDISPATCHED) {
                    source.select(
                        "one",
                        "report.pdf",
                        "application/pdf",
                        FILE_OUTPUT_BLOCK_BYTES + 1L,
                    )
                }
            native.choose()
            selection.await()
            assertIs<AppResult.Failed>(
                safeFileWriteCall { source.write("one", 0, ByteArray(FILE_OUTPUT_BLOCK_BYTES + 1)) }
            )
            assertEquals(0, native.written)
            assertEquals(1, native.aborted)
        } finally {
            source.close()
            native.restore()
        }
    }
}

private external interface OutputPickerFixture : JsAny {
    val opened: Int
    val written: Int
    val maximumWrite: Int
    val committed: Int
    val aborted: Int
    val openStarted: Promise<JsAny?>
    val writeStarted: Promise<JsAny?>
    val writeSettled: Promise<JsAny?>
    val pickerSettled: Promise<JsAny?>
    val abortObserved: Promise<JsAny?>

    fun choose()

    fun dismiss()

    fun releaseOpen()

    fun buffersCleared(): Boolean

    fun restore()
}

@JsFun(
    """(holdOpen, holdWrite) => {
    const original = globalThis.showSaveFilePicker;
    let pick, rejectPick, finishOpen, rejectWrite;
    let opened = 0, written = 0, maximumWrite = 0, committed = 0, aborted = 0;
    const buffers = [];
    let notifyOpen, notifyWrite, notifyWriteSettled, notifyPickerSettled, notifyAbort;
    const openStarted = new Promise(resolve => {notifyOpen = resolve;});
    const writeStarted = new Promise(resolve => {notifyWrite = resolve;});
    const writeSettled = new Promise(resolve => {notifyWriteSettled = resolve;});
    const pickerSettled = new Promise(resolve => {notifyPickerSettled = resolve;});
    const abortObserved = new Promise(resolve => {notifyAbort = resolve;});
    globalThis.showSaveFilePicker = () => new Promise((resolve, reject) => {
        pick = resolve; rejectPick = reject;
    }).then(value => {notifyPickerSettled(null); return value;});
    const handle = {createWritable: async () => {
        opened++; notifyOpen(null);
        if (holdOpen) await new Promise(resolve => {finishOpen = resolve;});
        return {
            write: async ({position, data}) => {
                buffers.push(data);
                maximumWrite = Math.max(maximumWrite, data.length);
                notifyWrite(null);
                try {
                    if (holdWrite) await new Promise((resolve, reject) => {rejectWrite = reject;});
                    if (position !== written) throw new Error('Unexpected write offset.');
                    written += data.length;
                } finally {
                    // Let the bridge's finally clear its buffer before the test resumes.
                    queueMicrotask(() => queueMicrotask(() => notifyWriteSettled(null)));
                }
            },
            close: async () => {committed++;},
            abort: async () => {
                aborted++; rejectWrite?.(new DOMException('Aborted.', 'AbortError'));
                notifyAbort(null);
            },
        };
    }};
    return {
        get opened() {return opened;}, get written() {return written;},
        get maximumWrite() {return maximumWrite;}, get committed() {return committed;},
        get aborted() {return aborted;}, openStarted, writeStarted, writeSettled, pickerSettled, abortObserved,
        choose() {pick(handle);}, dismiss() {rejectPick(new DOMException('Dismissed.', 'AbortError'));},
        releaseOpen() {finishOpen(null);},
        buffersCleared() {return buffers.every(bytes => bytes.every(value => value === 0));},
        restore() {globalThis.showSaveFilePicker = original;},
    };
}"""
)
private external fun createOutputPickerFixture(
    holdOpen: Boolean,
    holdWrite: Boolean,
): OutputPickerFixture

private fun installOutputPicker(
    holdOpen: Boolean = false,
    holdWrite: Boolean = false,
): OutputPickerFixture = createOutputPickerFixture(holdOpen, holdWrite)
