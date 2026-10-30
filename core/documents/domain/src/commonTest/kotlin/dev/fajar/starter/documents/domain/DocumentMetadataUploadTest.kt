package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class DocumentMetadataUploadTest {
    @Test
    fun printedHistoricalDatesAndCountryAreNormalizedWithoutInferringExpiry() {
        val value =
            assertIs<AppResult.Success<DocumentBusinessMetadata>>(
                    prepareDocumentBusinessMetadata(
                        DocumentMetadataInput(
                            DocumentCategory.PASSPORT,
                            "  A123456  ",
                            " au ",
                            " 2000-02-29 ",
                            "2010-02-28",
                        )
                    )
                )
                .value
        assertEquals(
            DocumentBusinessMetadata(
                DocumentCategory.PASSPORT,
                "A123456",
                "AU",
                LocalDate(2000, 2, 29),
                LocalDate(2010, 2, 28),
            ),
            value,
        )
        assertEquals(
            AppResult.Success(
                DocumentBusinessMetadata(DocumentCategory.OTHER, null, null, null, null)
            ),
            prepareDocumentBusinessMetadata(
                DocumentMetadataInput(identityNumber = " ", issuingCountry = " ")
            ),
        )
    }

    @Test
    fun invalidPrintedFactsAreRejectedBeforePersistingAnUpload() = runTest {
        val f = DocumentUploadFixture()
        val prepared = preparation(f)
        val initial = f.rows.value
        for (input in
            listOf(
                DocumentMetadataInput(issuingCountry = "ZZ"),
                DocumentMetadataInput(issuingCountry = "USA"),
                DocumentMetadataInput(issuedOn = "2023-02-29"),
                DocumentMetadataInput(issuedOn = "1/2/2020"),
                DocumentMetadataInput(issuedOn = "1899-12-31"),
                DocumentMetadataInput(validUntil = "2201-01-01"),
                DocumentMetadataInput(issuedOn = "2020-01-02", validUntil = "2020-01-01"),
                DocumentMetadataInput(identityNumber = "a".repeat(121)),
                DocumentMetadataInput(identityNumber = "A\n123"),
            )) {
            assertEquals(
                "client.document_metadata_invalid",
                assertIs<AppResult.Failed>(f.prepare(f.base.workspace.id, prepared, f.input, input))
                    .failure
                    .code,
            )
        }
        assertEquals(initial, f.rows.value)
        assertTrue(f.schedules.isEmpty())
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun unsupportedServerPreservesLegacyOmissionAndNeverQueuesNewMetadata() = runTest {
        val f = DocumentUploadFixture()
        val prepared = preparation(f)
        val unsupported =
            prepared.copy(employments = prepared.employments.copy(metadataSupported = false))
        assertEquals(
            "client.document_metadata_unsupported",
            assertIs<AppResult.Failed>(
                    f.prepare(f.base.workspace.id, unsupported, f.input, DocumentMetadataInput())
                )
                .failure
                .code,
        )
        assertNull(f.rows.value.single().upload)
        val legacy =
            assertIs<AppResult.Success<LocalDocumentFile>>(
                    f.prepare(f.base.workspace.id, unsupported, f.input)
                )
                .value
        assertEquals(f.input, legacy.upload?.intent?.input)
        assertNull(legacy.upload?.intent?.input?.metadata)
    }

    @Test
    fun responseLossAndRestartKeepTheOriginalMetadataAndOperation() = runTest {
        val f = DocumentUploadFixture(17)
        val prepared = preparation(f)
        val draft =
            DocumentMetadataInput(
                DocumentCategory.WORK_PERMIT,
                "AB-123",
                "ID",
                "2020-01-01",
                "2021-01-01",
            )
        val frozen =
            assertIs<AppResult.Success<LocalDocumentFile>>(
                    f.prepare(f.base.workspace.id, prepared, f.input, draft)
                )
                .value
                .upload!!
        val submitted = mutableListOf<DocumentUploadIntent>()
        f.startCall = {
            submitted += it
            AppResult.Failed(Failure(FailureKind.Network, "Lost response"))
        }
        f.replay()
        assertEquals(
            "client.document_upload_active",
            assertIs<AppResult.Failed>(
                    f.prepare(
                        f.base.workspace.id,
                        prepared,
                        f.input,
                        draft.copy(identityNumber = "Different"),
                    )
                )
                .failure
                .code,
        )
        f.advanceClock(30.seconds)
        f.startCall = {
            submitted += it
            AppResult.Success(DocumentMutationReceipt(it.revisionId, 0))
        }
        f.newReplayer()(f.base.workspace.id, f.file.draft.id)
        assertEquals(listOf(frozen.intent, frozen.intent), submitted)
        assertEquals(1, f.sent.filter { it.startsWith("start:") }.distinct().size)
        assertEquals(frozen.intent, f.rows.value.single().upload?.intent)
        assertIs<DocumentUploadStep.Finished>(f.rows.value.single().upload?.step)
    }

    @Test
    fun cancelledDeliveryKeepsFrozenMetadataForLaterRecovery() = runTest {
        val f = DocumentUploadFixture(17)
        val prepared = preparation(f)
        val metadata = DocumentMetadataInput(DocumentCategory.NATIONAL_ID, "1234", "ID")
        val original =
            assertIs<AppResult.Success<LocalDocumentFile>>(
                    f.prepare(f.base.workspace.id, prepared, f.input, metadata)
                )
                .value
                .upload!!
        val started = CompletableDeferred<Unit>()
        f.startCall = {
            started.complete(Unit)
            awaitCancellation()
        }
        val pending = async { f.replay() }
        started.await()
        pending.cancelAndJoin()
        assertEquals(original.intent, f.rows.value.single().upload?.intent)
        assertEquals(original.step, f.rows.value.single().upload?.step)
        assertFalse(canRemoveDocumentFile(f.rows.value.single()))
    }

    private suspend fun preparation(f: DocumentUploadFixture): DocumentUploadPreparation {
        val prepared =
            assertIs<AppResult.Success<DocumentUploadPreparation>>(
                    f.load(f.base.workspace.id, f.file.draft.id)
                )
                .value
        return prepared.copy(employments = prepared.employments.copy(metadataSupported = true))
    }
}
