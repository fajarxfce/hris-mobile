package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.canRemoveDocumentFile
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class DocumentRejectedUploadTest {
    @Test
    fun firstDefiniteRejectionCanBeReviewedAndReconfirmedWithoutReimportingBytes() = runTest {
        val f = DocumentUploadFixture(17)
        f.confirmed()
        val old = f.rows.value.single()
        f.startCall = { AppResult.Failed(rejection()) }
        f.replay()
        val rejected = f.rows.value.single()
        val form =
            assertIs<AppResult.Success<DocumentUploadPreparation>>(
                    f.load(f.base.workspace.id, f.file.draft.id)
                )
                .value
        assertEquals(
            rejected,
            f.rows.value.single(),
            "Reviewing cannot release the rejected intent",
        )
        assertTrue(canRemoveDocumentFile(rejected))
        val revised =
            assertIs<AppResult.Success<LocalDocumentFile>>(
                    f.prepare(f.base.workspace.id, form, f.input.copy(title = "Corrected title"))
                )
                .value
        assertEquals(old.draft, revised.draft)
        assertEquals(old.sha256, revised.sha256)
        assertEquals(old.storedBytes, revised.storedBytes)
        assertNotEquals(old.upload?.intent?.documentId, revised.upload?.intent?.documentId)
        assertNotEquals(old.upload?.intent?.revisionId, revised.upload?.intent?.revisionId)
        assertNotEquals(old.upload?.step, revised.upload?.step)
        assertEquals("Corrected title", revised.upload?.intent?.input?.title)
        assertEquals(0, revised.upload?.attempts)
        assertNull(revised.upload?.failure)
        assertFalse(canRemoveDocumentFile(revised))
        assertEquals(1, f.sent.size)
    }

    @Test
    fun unchangedInputMayStartAFreshOperationAfterAConfirmedRejection() = runTest {
        val f = DocumentUploadFixture(17)
        val first = f.confirmed()
        f.startCall = { AppResult.Failed(rejection()) }
        f.replay()
        val form =
            assertIs<AppResult.Success<DocumentUploadPreparation>>(
                    f.load(f.base.workspace.id, f.file.draft.id)
                )
                .value
        val next =
            assertIs<AppResult.Success<LocalDocumentFile>>(
                    f.prepare(f.base.workspace.id, form, f.input)
                )
                .value
        assertEquals(first.upload?.intent?.input, next.upload?.intent?.input)
        assertNotEquals(first.upload?.step, next.upload?.step)
    }

    @Test
    fun laterRejectionCannotDisproveAnEarlierLostResponseAfterRestart() = runTest {
        val f = DocumentUploadFixture(17)
        val first = f.confirmed()
        f.startCall = { AppResult.Failed(Failure(FailureKind.Network, "Lost response")) }
        f.replay()
        f.advanceClock(30.seconds)
        f.startCall = { AppResult.Failed(rejection()) }
        f.newReplayer()(f.base.workspace.id, f.file.draft.id)
        val retained = f.rows.value.single()
        assertEquals(first.upload?.intent, retained.upload?.intent)
        assertEquals(2, retained.upload?.attempts)
        assertFalse(canRemoveDocumentFile(retained))
        assertEquals(
            "client.document_upload_active",
            assertIs<AppResult.Failed>(f.load(f.base.workspace.id, f.file.draft.id)).failure.code,
        )
        assertEquals(retained, f.rows.value.single())
    }

    @Test
    fun unrecognizedServerErrorsNeverReleaseTheOriginalUpload() = runTest {
        val f = DocumentUploadFixture(17)
        f.confirmed()
        f.startCall = {
            AppResult.Failed(
                Failure(FailureKind.Validation, "Unknown", code = "unknown_server_rejection")
            )
        }
        f.replay()
        assertFalse(canRemoveDocumentFile(f.rows.value.single()))
        assertIs<AppResult.Failed>(f.load(f.base.workspace.id, f.file.draft.id))
    }

    @Test
    fun aKnownRejectedOperationIsNeverResentByManualOrAutomaticDelivery() = runTest {
        val f = DocumentUploadFixture(17)
        f.confirmed()
        f.startCall = { AppResult.Failed(rejection()) }
        f.replay()
        val original = f.rows.value.single()
        f.advanceClock(180.seconds)
        f.replay()
        f.newReplayer().invoke()
        assertEquals(1, f.sent.size)
        assertEquals(original, f.rows.value.single())
    }

    @Test
    fun anotherConfirmationWinsByFileRevisionAndDoesNotLoseItsIntent() = runTest {
        val f = DocumentUploadFixture(17)
        f.confirmed()
        f.startCall = { AppResult.Failed(rejection()) }
        f.replay()
        val form =
            assertIs<AppResult.Success<DocumentUploadPreparation>>(
                    f.load(f.base.workspace.id, f.file.draft.id)
                )
                .value
        val both = CompletableDeferred<Unit>()
        var entered = 0
        f.beforeUpdate = { _, _ ->
            if (++entered == 2) both.complete(Unit)
            both.await()
        }
        val first = async { f.prepare(f.base.workspace.id, form, f.input.copy(title = "First")) }
        val second = async { f.prepare(f.base.workspace.id, form, f.input.copy(title = "Second")) }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it is AppResult.Success })
        assertEquals(
            "client.document_file_changed",
            assertIs<AppResult.Failed>(results.single { it is AppResult.Failed }).failure.code,
        )
        assertEquals(
            assertIs<AppResult.Success<LocalDocumentFile>>(
                    results.single { it is AppResult.Success }
                )
                .value,
            f.rows.value.single(),
        )
    }

    @Test
    fun cancellationAfterReplacementPersistsTheNewOriginalIntentForRecovery() = runTest {
        val f = DocumentUploadFixture(17)
        val first = f.confirmed()
        f.startCall = { AppResult.Failed(rejection()) }
        f.replay()
        val form =
            assertIs<AppResult.Success<DocumentUploadPreparation>>(
                    f.load(f.base.workspace.id, f.file.draft.id)
                )
                .value
        val saved = CompletableDeferred<Unit>()
        f.afterUpdate = {
            saved.complete(Unit)
            awaitCancellation()
        }
        val confirmation = async {
            f.prepare(f.base.workspace.id, form, f.input.copy(title = "Corrected"))
        }
        saved.await()
        confirmation.cancelAndJoin()
        val retained = f.rows.value.single()
        assertNotEquals(first.upload?.intent, retained.upload?.intent)
        assertEquals("Corrected", retained.upload?.intent?.input?.title)
        assertFalse(canRemoveDocumentFile(retained))
        f.afterUpdate = {}
        f.startCall = { AppResult.Success(DocumentMutationReceipt(it.revisionId, 0)) }
        f.newReplayer()(f.base.workspace.id, f.file.draft.id)
        assertEquals(retained.upload?.intent, f.rows.value.single().upload?.intent)
        assertIs<DocumentUploadStep.Finished>(f.rows.value.single().upload?.step)
    }

    @Test
    fun capabilityLossCannotSilentlyDiscardTheRejectedDocumentFacts() = runTest {
        val f = DocumentUploadFixture(17)
        val prepared =
            assertIs<AppResult.Success<DocumentUploadPreparation>>(
                    f.load(f.base.workspace.id, f.file.draft.id)
                )
                .value
        val capable =
            prepared.copy(employments = prepared.employments.copy(metadataSupported = true))
        val metadata = DocumentBusinessMetadata(DocumentCategory.PASSPORT, "A123", "AU", null, null)
        assertIs<AppResult.Success<LocalDocumentFile>>(
            f.prepare(f.base.workspace.id, capable, f.input.copy(metadata = metadata))
        )
        f.startCall = { AppResult.Failed(rejection()) }
        f.replay()
        val rejected = f.rows.value.single()
        assertEquals(
            "client.document_metadata_unsupported",
            assertIs<AppResult.Failed>(f.load(f.base.workspace.id, f.file.draft.id)).failure.code,
        )
        assertEquals(
            "client.document_metadata_unsupported",
            assertIs<AppResult.Failed>(
                    f.prepare(f.base.workspace.id, prepared.copy(file = rejected), f.input)
                )
                .failure
                .code,
        )
        assertEquals(rejected, f.rows.value.single())
    }

    private fun rejection() =
        Failure(FailureKind.Validation, "Metadata rejected", code = "invalid_document_metadata")
}
