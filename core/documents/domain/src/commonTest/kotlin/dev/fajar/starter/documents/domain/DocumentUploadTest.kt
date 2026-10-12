package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class DocumentUploadTest {
    @Test
    fun reviewingConcurrentProgressPreservesTheCancellationIntent() = runTest {
        val f = DocumentUploadFixture()
        val file = f.confirmed()
        val upload =
            requireNotNull(file.upload)
                .copy(
                    step =
                        DocumentUploadStep.Cancel(
                            "77777777-7777-7777-7777-777777777777",
                            0,
                            "Selected another file",
                        ),
                    attempts = 1,
                )
        val progress =
            f.status.copy(
                status = DocumentRevisionStatus.UPLOADING,
                version = 1,
                uploadedBytes = DOCUMENT_UPLOAD_CHUNK_BYTES.toLong(),
            )
        val reviewed =
            assertIs<AppResult.Success<DocumentUpload>>(
                    resolveDocumentUploadReview(
                        upload,
                        progress,
                        f.file.draft.declaration.size,
                        DocumentUploadAction.CheckStatus,
                        "88888888-8888-8888-8888-888888888888",
                    )
                )
                .value
        val step = assertIs<DocumentUploadStep.Cancel>(reviewed.step)
        assertEquals(1L, step.version)
        assertEquals("Selected another file", step.reason)
        assertEquals(upload.intent, reviewed.intent)
        assertEquals(0, reviewed.attempts)
    }

    @Test
    fun confirmationIsDurableBeforeSchedulingAndInvalidInputHasNoSideEffects() = runTest {
        val f = DocumentUploadFixture()
        val form =
            assertIs<AppResult.Success<DocumentUploadPreparation>>(
                    f.load(f.base.workspace.id, f.file.draft.id)
                )
                .value
        assertIs<AppResult.Failed>(f.prepare(f.base.workspace.id, form, f.input.copy(title = " ")))
        assertNull(f.rows.value.single().upload)
        assertTrue(f.sent.isEmpty() && f.schedules.isEmpty())
        val confirmed =
            assertIs<AppResult.Success<LocalDocumentFile>>(
                    f.prepare(
                        f.base.workspace.id,
                        form,
                        f.input.copy(title = "  Personal document  "),
                    )
                )
                .value
        assertEquals(f.input, confirmed.upload?.intent?.input)
        assertEquals(confirmed, f.rows.value.single())
        assertEquals(listOf(DOCUMENT_UPLOAD_TASK), f.schedules)
        assertTrue(f.sent.isEmpty())
        assertEquals(AppResult.Success(confirmed), f.prepare(f.base.workspace.id, form, f.input))
    }

    @Test
    fun expiredOrReplacedConfirmationCannotQueueAnUpload() = runTest {
        val f = DocumentUploadFixture()
        val form =
            assertIs<AppResult.Success<DocumentUploadPreparation>>(
                    f.load(f.base.workspace.id, f.file.draft.id)
                )
                .value
        f.advanceClock(61.seconds)
        assertEquals(
            "client.document_preparation_expired",
            assertIs<AppResult.Failed>(f.prepare(f.base.workspace.id, form, f.input)).failure.code,
        )
        assertNull(f.rows.value.single().upload)
        f.base.employmentCall = {
            f.base.sessions.value = AppResult.Success(null)
            AppResult.Success(form.employments)
        }
        assertIs<AppResult.Failed>(f.load(f.base.workspace.id, f.file.draft.id))
        assertTrue(f.schedules.isEmpty())
    }

    @Test
    fun competingConfirmationsKeepOneImmutableIntent() = runTest {
        val f = DocumentUploadFixture()
        val form =
            assertIs<AppResult.Success<DocumentUploadPreparation>>(
                    f.load(f.base.workspace.id, f.file.draft.id)
                )
                .value
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var calls = 0
        f.beforeUpdate = { _, _ ->
            if (++calls == 1) {
                entered.complete(Unit)
                resume.await()
            }
        }
        val first = async { f.prepare(f.base.workspace.id, form, f.input) }
        entered.await()
        val accepted =
            assertIs<AppResult.Success<LocalDocumentFile>>(
                    f.prepare(f.base.workspace.id, form, f.input.copy(title = "Second"))
                )
                .value
        resume.complete(Unit)
        assertIs<AppResult.Failed>(first.await())
        assertEquals(accepted, f.rows.value.single())
        assertEquals(1, f.schedules.size)
    }

    @Test
    fun boundedChunksResumeAfterRecreationAndOnlyServerReadyFinishes() = runTest {
        val f = DocumentUploadFixture()
        f.confirmed()
        val pending = assertIs<AppResult.Success<LocalDocumentFile>>(f.replay()).value
        assertIs<DocumentUploadStep.Processing>(pending.upload?.step)
        assertEquals(
            listOf("start", "chunk", "chunk", "validate"),
            f.sent.map { it.substringBefore(':') },
        )
        assertTrue(f.sent[1].contains(":0:1048576:"))
        assertTrue(f.sent[2].contains(":1048576:7:"))
        assertTrue(f.buffers.all { bytes -> bytes.all { it == 0.toByte() } })
        assertEquals(SyncResult.Complete, f.newReplayer().invoke())
        val finished = assertIs<DocumentUploadStep.Finished>(f.rows.value.single().upload?.step)
        assertEquals(DocumentRevisionStatus.READY, finished.revision.status)
        assertEquals(
            AppResult.Success(Unit),
            f.remove(f.base.workspace.id, f.file.draft.id, f.rows.value.single().revision),
        )
    }

    @Test
    fun lostStartResponseReusesIdentityAndWaitsBeforeAnotherAttempt() = runTest {
        val f = DocumentUploadFixture()
        val first = f.confirmed()
        f.startCall = { AppResult.Failed(Failure(FailureKind.Network, "Lost")) }
        f.replay()
        val pending = f.rows.value.single()
        assertEquals(first.upload?.step, pending.upload?.step)
        assertIs<AppResult.Failed>(f.remove(f.base.workspace.id, f.file.draft.id, pending.revision))
        f.replay()
        assertEquals(1, f.sent.size)
        f.advanceClock(30.seconds)
        f.startCall = { AppResult.Success(DocumentMutationReceipt(it.revisionId, 0)) }
        f.newReplayer()(f.base.workspace.id, f.file.draft.id)
        assertEquals(f.sent.first(), f.sent[1])
        assertEquals(first.upload?.intent, f.rows.value.single().upload?.intent)
    }

    @Test
    fun cancelledChunkClearsBufferAndRetainsOriginalOffsetAndKey() = runTest {
        val f = DocumentUploadFixture()
        f.confirmed()
        val pending = CompletableDeferred<Unit>()
        f.chunkCall = { _, _ ->
            pending.complete(Unit)
            awaitCancellation()
        }
        val request = launch { f.replay() }
        pending.await()
        val original = f.sent.last()
        assertTrue(f.buffers.single().any { it != 0.toByte() })
        request.cancelAndJoin()
        assertTrue(f.buffers.single().all { it == 0.toByte() })
        assertIs<DocumentUploadStep.Chunk>(f.rows.value.single().upload?.step)
        f.newReplayer()(f.base.workspace.id, f.file.draft.id)
        assertEquals(2, f.sent.size)
        f.advanceClock(120.seconds)
        f.chunkCall = { id, offset ->
            AppResult.Success(DocumentMutationReceipt(id, offset / DOCUMENT_UPLOAD_CHUNK_BYTES + 1))
        }
        f.newReplayer()(f.base.workspace.id, f.file.draft.id)
        assertEquals(original, f.sent[2])
        assertTrue(f.buffers.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun admissionChangeDuringChunkAcquisitionPreventsTransport() = runTest {
        val f = DocumentUploadFixture()
        f.confirmed()
        f.beforeChunk = { f.base.sessions.value = AppResult.Success(null) }
        assertIs<AppResult.Failed>(f.replay())
        assertEquals(listOf("start"), f.sent.map { it.substringBefore(':') })
        assertTrue(f.buffers.single().all { it == 0.toByte() })
    }

    @Test
    fun lateReceiptCannotOverwriteAConcurrentCheckpoint() = runTest {
        val f = DocumentUploadFixture()
        f.confirmed()
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        f.startCall = {
            started.complete(Unit)
            resume.await()
            AppResult.Success(DocumentMutationReceipt(it.revisionId, 0))
        }
        val request = async { f.replay() }
        started.await()
        val newer = f.rows.value.single().copy(revision = f.rows.value.single().revision + 1)
        f.rows.value = listOf(newer)
        resume.complete(Unit)
        assertEquals(
            "client.document_file_changed",
            assertIs<AppResult.Failed>(request.await()).failure.code,
        )
        assertEquals(newer, f.rows.value.single())
        assertEquals(1, f.sent.size)
    }

    @Test
    fun chunkLeaseAndAutomaticAttemptBudgetAreRespected() = runTest {
        val f = DocumentUploadFixture()
        f.confirmed()
        val lease = f.base.now + 120.seconds
        f.chunkCall = { _, _ ->
            AppResult.Failed(
                Failure(
                    FailureKind.Conflict,
                    "Pending",
                    code = "document_chunk_in_progress",
                    parameters = mapOf("retryAt" to lease.toString()),
                )
            )
        }
        f.replay()
        assertEquals(lease, f.rows.value.single().upload?.nextAttemptAt)
        val calls = f.sent.size
        assertIs<SyncResult.Retry>(f.replay.invoke())
        assertEquals(calls, f.sent.size)
        val pending = f.rows.value.single()
        f.rows.value =
            listOf(
                pending.copy(
                    upload =
                        requireNotNull(pending.upload)
                            .copy(
                                attempts = DOCUMENT_UPLOAD_AUTOMATIC_ATTEMPTS,
                                nextAttemptAt = null,
                            )
                )
            )
        assertIs<SyncResult.Blocked>(f.replay.invoke())
        assertEquals(calls, f.sent.size)
    }

    @Test
    fun validationFailureRequiresAnExplicitRetryWithFreshVersion() = runTest {
        val f = DocumentUploadFixture(17)
        f.confirmed()
        f.status =
            f.status.copy(
                status = DocumentRevisionStatus.VALIDATION_FAILED,
                failureCode = "document_scanner_unavailable",
            )
        f.replay()
        assertEquals(
            DocumentRevisionStatus.VALIDATION_FAILED,
            assertIs<DocumentUploadStep.Finished>(f.rows.value.single().upload?.step)
                .revision
                .status,
        )
        assertEquals(SyncResult.Complete, f.replay.invoke())
        assertEquals(1, f.sent.count { it.startsWith("validate:") })
        val request =
            assertIs<AppResult.Success<LocalDocumentFile>>(
                    f.review(
                        f.base.workspace.id,
                        f.file.draft.id,
                        DocumentUploadAction.RetryValidation,
                    )
                )
                .value
        assertIs<DocumentUploadStep.Validate>(request.upload?.step)
        f.status =
            f.status.copy(status = DocumentRevisionStatus.READY, version = 5, failureCode = null)
        f.replay()
        val validations = f.sent.filter { it.startsWith("validate:") }
        assertEquals(2, validations.size)
        assertNotEquals(
            validations[0].substringBeforeLast(':'),
            validations[1].substringBeforeLast(':'),
        )
        assertEquals(
            DocumentRevisionStatus.READY,
            assertIs<DocumentUploadStep.Finished>(f.rows.value.single().upload?.step)
                .revision
                .status,
        )
    }

    @Test
    fun cancellationIsPersistedBeforeTransportAndUnknownStatusCannotDeleteIntent() = runTest {
        val f = DocumentUploadFixture(17)
        f.confirmed()
        f.startCall = { AppResult.Failed(Failure(FailureKind.Network, "Lost")) }
        f.replay()
        f.advanceClock(30.seconds)
        val before = f.rows.value.single()
        f.revisionCall = {
            AppResult.Failed(Failure(FailureKind.Unavailable, "Not found", code = "not_found"))
        }
        assertIs<AppResult.Failed>(
            f.review(
                f.base.workspace.id,
                f.file.draft.id,
                DocumentUploadAction.Cancel("Selected another file"),
            )
        )
        assertEquals(before, f.rows.value.single())
        f.status =
            f.status.copy(status = DocumentRevisionStatus.UPLOADING, uploadedBytes = 0, version = 0)
        f.revisionCall = { AppResult.Success(f.status) }
        val prepared =
            assertIs<AppResult.Success<LocalDocumentFile>>(
                    f.review(
                        f.base.workspace.id,
                        f.file.draft.id,
                        DocumentUploadAction.Cancel("Selected another file"),
                    )
                )
                .value
        assertIs<DocumentUploadStep.Cancel>(prepared.upload?.step)
        assertTrue(f.sent.none { it.startsWith("cancel:") })
        f.replay()
        assertEquals(
            DocumentRevisionStatus.CANCELLED,
            assertIs<DocumentUploadStep.Finished>(f.rows.value.single().upload?.step)
                .revision
                .status,
        )
        assertEquals(before.upload?.intent, f.rows.value.single().upload?.intent)
    }

    @Test
    fun wrongReceiptNeverAdvancesConfirmedProgress() = runTest {
        val f = DocumentUploadFixture()
        val original = f.confirmed()
        f.startCall = { AppResult.Success(DocumentMutationReceipt(it.revisionId, 9)) }
        f.replay()
        assertEquals(original.upload?.step, f.rows.value.single().upload?.step)
        assertEquals("client.invalid_response", f.rows.value.single().upload?.failure?.code)
        assertEquals(1, f.sent.size)
    }
}
