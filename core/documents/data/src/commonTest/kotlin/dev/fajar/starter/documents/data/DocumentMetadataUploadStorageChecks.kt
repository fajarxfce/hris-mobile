package dev.fajar.starter.documents.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.AppDatabase
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

suspend fun verifyDocumentMetadataUploadStorage(database: AppDatabase) {
    val f = DocumentFileStorageFixture(database)
    f.activate()
    val file = imported(f)
    val metadata =
        DocumentBusinessMetadata(
            DocumentCategory.PASSPORT,
            "AB-123",
            "AU",
            LocalDate(2000, 2, 29),
            LocalDate(2010, 2, 28),
        )
    val upload =
        DocumentUpload(
            DocumentUploadIntent(
                "44444444-4444-4444-4444-444444444444",
                "55555555-5555-5555-5555-555555555555",
                DocumentUploadInput(
                    "66666666-6666-6666-6666-666666666666",
                    "Passport",
                    DocumentClassification.PERSONAL,
                    "Supporting evidence",
                    metadata,
                ),
                f.draft.createdAt,
            ),
            DocumentUploadStep.Start("77777777-7777-7777-7777-777777777777"),
        )
    assertEquals(AppResult.Success(true), f.repository().updateUpload(f.access, file, upload))
    val encrypted = requireNotNull(database.blobs.get(f.scope, f.partition, file.draft.id)?.state)
    assertFalse(encrypted.contains("AB-123"))
    val restoredAccess = f.access.copy(sessionId = "restored")
    database.accounts.activate(restoredAccess.sessionId, f.scope.accountKey)
    val restored =
        assertIs<AppResult.Success<LocalDocumentFile?>>(
                f.repository().find(restoredAccess, file.draft.id)
            )
            .value!!
    assertEquals(upload, restored.upload)
    assertEquals(
        AppResult.Success(false),
        f.repository()
            .updateUpload(
                restoredAccess,
                restored,
                upload.copy(
                    intent =
                        upload.intent.copy(
                            input =
                                upload.intent.input.copy(
                                    metadata = metadata.copy(identityNumber = "Changed")
                                )
                        )
                ),
            ),
    )
    assertEquals(
        restored,
        assertIs<AppResult.Success<LocalDocumentFile?>>(
                f.repository().find(restoredAccess, file.draft.id)
            )
            .value,
    )
}

suspend fun verifyLegacyUploadMetadataOmission(database: AppDatabase) {
    val f = DocumentFileStorageFixture(database)
    f.activate()
    val file = imported(f)
    // This persisted format predates metadata. Seal the old JSON directly, not through the new DTO.
    val legacy =
        """{"documentId":"44444444-4444-4444-4444-444444444444","revisionId":"55555555-5555-5555-5555-555555555555","employmentId":"66666666-6666-6666-6666-666666666666","title":"Old upload","classification":"PERSONAL","reason":"Supporting file","createdAt":"2026-10-12T00:00:00Z","step":{"type":"start","operationId":"77777777-7777-7777-7777-777777777777"},"attempts":1,"nextAttemptAt":null,"failure":null}"""
    val aad =
        Json.encodeToString(
                listOf(
                    "hris-document-file-v1",
                    f.access.audience,
                    f.access.accountId,
                    f.access.companyId,
                    file.draft.id,
                    file.draft.declaration.size.toString(),
                    "upload",
                    (file.revision + 1).toString(),
                )
            )
            .encodeToByteArray()
    val key = Base64.decode(requireNotNull(f.keys.read()))
    try {
        val encrypted = Base64.encode(f.cipher.encrypt(key, aad, legacy.encodeToByteArray()))
        assertTrue(
            database.blobs.updateState(
                f.scope,
                f.partition,
                file.draft.id,
                file.revision,
                encrypted,
            )
        )
    } finally {
        key.fill(0)
    }
    val restored =
        assertIs<AppResult.Success<LocalDocumentFile?>>(
                f.repository().find(f.access, file.draft.id)
            )
            .value!!
    val upload = requireNotNull(restored.upload)
    assertNull(upload.intent.input.metadata)
    assertEquals(
        "77777777-7777-7777-7777-777777777777",
        assertIs<DocumentUploadStep.Start>(upload.step).operationId,
    )
    assertEquals(
        AppResult.Success(true),
        f.repository().updateUpload(f.access, restored, upload.copy(attempts = 2)),
    )
    val rewritten =
        assertIs<AppResult.Success<LocalDocumentFile?>>(
                f.repository().find(f.access, file.draft.id)
            )
            .value!!
    assertEquals(upload.intent, rewritten.upload?.intent)
    assertNull(rewritten.upload?.intent?.input?.metadata)
}

private suspend fun imported(f: DocumentFileStorageFixture): LocalDocumentFile =
    assertIs<DocumentFileReservation.Stored>(
            assertIs<AppResult.Success<DocumentFileReservation>>(
                    f.repository()
                        .import(f.access, f.draft, DOCUMENT_LOCAL_CAPACITY, DOCUMENT_LOCAL_FILES)
                )
                .value
        )
        .file
