package dev.fajar.starter.documents.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.AppDatabase
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import kotlin.test.*
import kotlinx.coroutines.*

suspend fun verifyRejectedUploadReplacement(database: AppDatabase) = coroutineScope {
    val f = DocumentFileStorageFixture(database)
    f.activate()
    val file =
        assertIs<DocumentFileReservation.Stored>(
                assertIs<AppResult.Success<DocumentFileReservation>>(
                        f.repository()
                            .import(
                                f.access,
                                f.draft,
                                DOCUMENT_LOCAL_CAPACITY,
                                DOCUMENT_LOCAL_FILES,
                            )
                    )
                    .value
            )
            .file
    val original =
        DocumentUpload(
            DocumentUploadIntent(
                "44444444-4444-4444-4444-444444444444",
                "55555555-5555-5555-5555-555555555555",
                DocumentUploadInput(
                    "66666666-6666-6666-6666-666666666666",
                    "Original",
                    DocumentClassification.PERSONAL,
                    "Supporting evidence",
                    DocumentBusinessMetadata(DocumentCategory.PASSPORT, "AB-123", "AU", null, null),
                ),
                f.draft.createdAt,
            ),
            DocumentUploadStep.Start("77777777-7777-7777-7777-777777777777"),
            attempts = 1,
            failure =
                Failure(FailureKind.Validation, "Rejected", code = "invalid_document_metadata"),
        )
    assertEquals(AppResult.Success(true), f.repository().updateUpload(f.access, file, original))
    val rejected =
        assertIs<AppResult.Success<LocalDocumentFile?>>(
                f.repository().find(f.access, file.draft.id)
            )
            .value!!
    assertTrue(isDocumentUploadRejected(rejected.upload))
    val first =
        DocumentUpload(
            original.intent.copy(
                documentId = "88888888-8888-8888-8888-888888888888",
                revisionId = "99999999-9999-9999-9999-999999999999",
                input = original.intent.input.copy(title = "Corrected"),
            ),
            DocumentUploadStep.Start("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        )
    val second =
        first.copy(intent = first.intent.copy(documentId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
    val a = async { f.repository().replaceUpload(f.access, rejected, first) }
    val b = async { f.repository().replaceUpload(f.access, rejected, second) }
    assertEquals(1, listOf(a.await(), b.await()).count { it == AppResult.Success(true) })
    val retained =
        assertIs<AppResult.Success<LocalDocumentFile?>>(
                f.repository().find(f.access, file.draft.id)
            )
            .value!!
    assertTrue(retained.upload in listOf(first, second))
    assertEquals(file.draft, retained.draft)
    assertEquals(file.sha256, retained.sha256)
    assertEquals(file.storedBytes, retained.storedBytes)
    assertFalse(canRemoveDocumentFile(retained))
    assertEquals(AppResult.Success(false), f.repository().replaceUpload(f.access, rejected, first))
    assertEquals(
        AppResult.Success(false),
        f.repository()
            .updateUpload(
                f.access,
                retained,
                requireNotNull(retained.upload).copy(intent = original.intent),
            ),
    )
    assertEquals(
        retained,
        assertIs<AppResult.Success<LocalDocumentFile?>>(
                f.repository().find(f.access, file.draft.id)
            )
            .value,
    )
}
