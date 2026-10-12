@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.fileio

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class BrowserFileInputSourceTest {
    @Test
    fun selectingAndReadingAFileUsesBoundedBlocksAndRemovesThePicker() = runTest {
        val source = BrowserFileInputSource()
        try {
            val selection =
                async(start = CoroutineStart.UNDISPATCHED) {
                    source.select("one", setOf("application/pdf"))
                }
            assertEquals(1, pickerCount())
            chooseFile(FILE_INPUT_BLOCK_BYTES * 2 + 97)
            assertEquals(
                FileInputMetadata("sample.pdf", "application/pdf", 131169, 1000),
                selection.await(),
            )
            assertEquals(0, pickerCount())
            val blocks = source.read("one").toList()
            assertEquals(
                listOf(FILE_INPUT_BLOCK_BYTES, FILE_INPUT_BLOCK_BYTES, 97),
                blocks.map { it.size },
            )
            var offset = 0
            blocks.forEach { block ->
                block.forEach { assertEquals((offset++ % 251).toByte(), it) }
            }
            source.release("one")
            assertFailsWith<FileInputReleasedException> { source.read("one").collect() }
        } finally {
            source.close()
        }
    }

    @Test
    fun cancellationAndUserDismissalDoNotLeavePickersOrBusySlots() = runTest {
        val source = BrowserFileInputSource()
        try {
            val abandoned =
                async(start = CoroutineStart.UNDISPATCHED) {
                    source.select("one", setOf("application/pdf"))
                }
            assertEquals(1, pickerCount())
            abandoned.cancelAndJoin()
            assertEquals(0, pickerCount())
            val dismissed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    source.select("one", setOf("application/pdf"))
                }
            dismissPicker()
            assertNull(dismissed.await())
            assertEquals(0, pickerCount())
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    source.select("next", setOf("application/pdf"))
                }
            source.close()
            pending.join()
            assertTrue(pending.isCancelled)
            assertEquals(0, pickerCount())
            assertFailsWith<FileInputUnavailableException> {
                source.select("closed", setOf("application/pdf"))
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun releasingAnInputStopsFurtherReadsAfterTheConsumerFinishesItsCurrentBlock() = runTest {
        val source = BrowserFileInputSource()
        try {
            val selection =
                async(start = CoroutineStart.UNDISPATCHED) {
                    source.select("file", setOf("application/pdf"))
                }
            chooseFile(FILE_INPUT_BLOCK_BYTES * 3)
            selection.await()
            val firstBlock = CompletableDeferred<Unit>()
            val consumed = CompletableDeferred<Unit>()
            var count = 0
            val reader = launch {
                source.read("file").collect {
                    count++
                    firstBlock.complete(Unit)
                    consumed.await()
                }
            }
            firstBlock.await()
            source.release("file")
            // The source owns native reads. The import use case owns processing already
            // delivered bytes and must cancel that work when its own lifetime ends.
            consumed.complete(Unit)
            reader.join()
            assertTrue(reader.isCancelled)
            assertEquals(1, count)
        } finally {
            source.close()
        }
    }

    @Test
    fun releasingAPendingNativeReadAbortsTheReaderAndCompletesTheFlow() = runTest {
        val source = BrowserFileInputSource()
        val native = pauseNativeReader()
        try {
            val selection =
                async(start = CoroutineStart.UNDISPATCHED) {
                    source.select("file", setOf("application/pdf"))
                }
            chooseFile(FILE_INPUT_BLOCK_BYTES)
            selection.await()
            val reader = launch { source.read("file").collect() }
            native.started.await<JsAny?>()
            source.release("file")
            reader.join()
            assertTrue(reader.isCancelled)
            assertEquals(1, native.aborted)
        } finally {
            source.close()
            native.restore()
        }
    }
}

private external interface PausedNativeReader : JsAny {
    val started: Promise<JsAny?>
    val aborted: Int

    fun restore()
}

@JsFun(
    """() => {
    const original = globalThis.FileReader;
    let started, aborted = 0;
    const promise = new Promise(resolve => { started = resolve; });
    globalThis.FileReader = class {
        static LOADING = 1;
        readyState = 0;
        readAsDataURL() { this.readyState = 1; started(null); }
        abort() { this.readyState = 2; aborted++; this.onabort?.(); }
    };
    return {started: promise, get aborted() {return aborted;},
            restore() {globalThis.FileReader = original;}};
}"""
)
private external fun pauseNativeReader(): PausedNativeReader

@JsFun("() => document.querySelectorAll('input[data-app-file-picker]').length")
private external fun pickerCount(): Int

@JsFun(
    """(size) => {
    const input = document.querySelector('input[data-app-file-picker]');
    const bytes = new Uint8Array(size);
    for (let index = 0; index < size; index++) bytes[index] = index % 251;
    const transfer = new DataTransfer();
    transfer.items.add(new File([bytes], 'sample.pdf', {type: 'application/pdf', lastModified: 1000}));
    input.files = transfer.files;
    input.dispatchEvent(new Event('change'));
}"""
)
private external fun chooseFile(size: Int)

@JsFun(
    """() => document.querySelector('input[data-app-file-picker]').dispatchEvent(new Event('cancel'))"""
)
private external fun dismissPicker()
