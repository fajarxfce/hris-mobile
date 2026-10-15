package dev.fajar.starter.payroll.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.data.repositories.RemotePayslipRepository
import dev.fajar.starter.payroll.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class PayslipPdfTest {
    @Test
    fun invalidOrAmbiguousPdfHeadersCannotBecomeExportMetadata() = runTest {
        val f = PayslipDataFixture()
        val valid = f.headers
        val repo = RemotePayslipRepository(f)
        for (headers in
            listOf(
                valid.copy(lengths = listOf("1048577")),
                valid.copy(lengths = listOf("0")),
                valid.copy(lengths = listOf("10", "10")),
                valid.copy(mediaTypes = listOf("text/html")),
                valid.copy(encodings = listOf("gzip")),
                valid.copy(entityTags = listOf("W/${f.metadata.entityTag}")),
                valid.copy(entityTags = listOf(f.metadata.entityTag, f.metadata.entityTag)),
            )) {
            f.headers = headers
            assertIs<AppResult.Failed>(
                repo.inspect(
                    f.access,
                    f.summary.id,
                    f.metadata.revision,
                    PayslipDocumentLanguage.ID,
                )
            )
        }
    }

    @Test
    fun rejectedPdfBytesAreClearedOnSignatureHashOrExtentMismatch() = runTest {
        for (kind in 0..2) {
            val f = PayslipDataFixture()
            val bytes =
                when (kind) {
                    0 -> "<html>private failure".encodeToByteArray()
                    1 -> f.bytes.copyOf().also { it[6] = 42 }
                    else -> f.bytes + byteArrayOf(1)
                }
            f.downloadCall = { bytes }
            assertIs<AppResult.Failed>(RemotePayslipRepository(f).download(f.access, f.metadata))
            assertTrue(bytes.all { it == 0.toByte() })
        }
    }

    @Test
    fun successfulBytesTransferOwnershipButCancelledLateAcquisitionErasesThem() = runTest {
        val valid = PayslipDataFixture()
        val bytes =
            assertIs<AppResult.Success<ByteArray>>(
                    RemotePayslipRepository(valid).download(valid.access, valid.metadata)
                )
                .value
        assertSame(valid.bytes, bytes)
        assertTrue(bytes.any { it != 0.toByte() })
        bytes.fill(0)
        val f = PayslipDataFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.downloadCall = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            f.bytes
        }
        val request = async { RemotePayslipRepository(f).download(f.access, f.metadata) }
        entered.await()
        request.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { request.await() }
        assertTrue(f.bytes.all { it == 0.toByte() })
    }
}
