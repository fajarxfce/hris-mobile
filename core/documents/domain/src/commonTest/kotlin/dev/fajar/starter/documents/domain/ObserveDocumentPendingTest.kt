package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.replica.CatalogSyncFixture
import dev.fajar.starter.documents.domain.usecases.ObserveDocumentPending
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ObserveDocumentPendingTest {
    @Test
    fun distinguishesImportsIncompleteTransfersValidationFailuresAndCompletedFiles() = runTest {
        val f = DocumentUploadFixture()
        val catalog = CatalogSyncFixture()
        val queued = f.confirmed()
        val source =
            DocumentDownloadSource(
                f.base.query.employmentId,
                DocumentClassification.PERSONAL,
                f.base.evidence,
            )
        val download = f.file.copy(draft = f.file.draft.copy(download = source))
        f.rows.value = listOf(f.file, queued, download, download.copy(sha256 = null))
        val observe =
            ObserveDocumentPending(
                f.files,
                catalog.store,
                f.base.sessionRepository,
                f.base.workspaceRepository,
                f.base.clock,
                f.build,
            )
        suspend fun summary() =
            assertIs<AppResult.Success<PendingActionSummary>>(
                    observe.observePending(f.base.workspace.id).first()
                )
                .value
        assertEquals(PendingActionSummary(drafts = 1, transfers = 2), summary())
        val upload = requireNotNull(queued.upload)
        for (status in DocumentRevisionStatus.entries) {
            f.rows.value =
                listOf(
                    queued.copy(
                        upload =
                            upload.copy(
                                step = DocumentUploadStep.Finished(f.status.copy(status = status))
                            )
                    )
                )
            assertEquals(
                PendingActionSummary(
                    needsReview = if (status == DocumentRevisionStatus.VALIDATION_FAILED) 1 else 0
                ),
                summary(),
            )
        }
        val denial = Failure(FailureKind.AccessDenied, "Verify", code = "mfa_required")
        catalog.store.restrict(catalog.access, denial)
        assertEquals(AppResult.Failed(denial), observe.observePending(f.base.workspace.id).first())
        assertTrue(f.sent.isEmpty())
    }
}
