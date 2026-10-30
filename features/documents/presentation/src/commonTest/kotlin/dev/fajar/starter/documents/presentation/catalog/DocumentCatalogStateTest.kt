package dev.fajar.starter.documents.presentation.catalog

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.documents.domain.replica.policies.initialDocumentCatalogCheckpoint
import kotlin.test.*
import kotlin.time.Instant

class DocumentCatalogStateTest {
    @Test
    fun aResourceDenialClearsItsPendingDownloadAndKeepsUnrelatedControlsAvailable() {
        val previous = populated().copy(preparingId = "revision")
        val snapshot = previous.catalog!!
        val hidden =
            snapshot.copy(
                page = snapshot.page.copy(items = emptyList()),
                restrictions =
                    listOf(
                        DocumentCatalogRestriction(
                            "document",
                            Failure(
                                FailureKind.AccessDenied,
                                "Denied",
                                code = "document_access_denied",
                            ),
                        )
                    ),
            )
        val next = previous.withCatalog(AppResult.Success(hidden))
        assertNull(next.preparingId)
        assertNull(next.restriction)
        assertTrue(next.canChoose)
        assertEquals(hidden, next.catalog)
    }

    @Test
    fun ordinaryCredentialAgeAndTechnicalFailuresKeepOwnedFacts() {
        val state = populated()
        for (failure in
            listOf(
                Failure(FailureKind.Unauthorized, "Expired", code = "session_expired"),
                Failure(FailureKind.Network, "Offline", code = "client.network"),
                Failure(FailureKind.Storage, "Unavailable", code = "client.storage"),
                Failure(
                    FailureKind.Unexpected,
                    "Expired policy",
                    code = "client.company_policy_expired",
                ),
            )) {
            val failed = state.withCatalogFailure(failure)
            assertEquals(state.catalog, failed.catalog)
            assertNull(failed.restriction)
            assertTrue(failed.canChoose)
        }
    }

    @Test
    fun confirmedReadDenialRemainsLatchedDuringPendingAndFailedRetry() {
        val denied =
            populated()
                .withCatalogFailure(
                    Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
                )
        assertNull(denied.catalog)
        assertNull(denied.employments)
        assertTrue(denied.needsVerification)
        val pending = denied.copy(syncing = true)
        val failed =
            pending.withCatalogFailure(
                Failure(FailureKind.Network, "Offline", code = "client.network")
            )
        assertEquals(denied.restriction, failed.restriction)
        assertNull(failed.catalog)
        assertFalse(failed.canChoose)
    }

    @Test
    fun aLateLocalPageCannotReplaceAnotherCategorySelection() {
        val previous = populated()
        val changed =
            previous.copy(
                selection =
                    previous.selection.copy(classification = DocumentClassification.RECEIPT),
                catalog = null,
            )
        assertEquals(changed, changed.withCatalog(AppResult.Success(previous.catalog!!)))
    }

    private fun populated(): DocumentCatalogState {
        val now = Instant.parse("2026-10-14T00:00:00Z")
        val access =
            DocumentAccess(
                "session",
                "account",
                "https://hris.example.test/",
                "company",
                setOf("documents.self.read"),
                now,
                now,
                "expired",
            )
        val employment =
            DocumentEmployment("employee", "EMP-001", DocumentEmploymentStatus.ACTIVE, 0)
        val selection = DocumentCatalogSelection(employment.id)
        val checkpoint =
            initialDocumentCatalogCheckpoint(access, "generation", now, listOf(employment))
                .copy(
                    revision = 1,
                    phase = DocumentCatalogSyncPhase.CHANGES,
                    cursor = "head",
                    published = true,
                    refreshedAt = now,
                )
        val page =
            DocumentCatalogPage(
                listOf(
                    DocumentCatalogRecord(
                        "document",
                        0,
                        employment.id,
                        DocumentClassification.PERSONAL,
                        DocumentEvidence(
                            "document",
                            "revision",
                            1,
                            "Medical certificate",
                            "certificate.pdf",
                            "application/pdf",
                            100,
                            "a".repeat(64),
                            now,
                        ),
                    )
                )
            )
        return DocumentCatalogState(
            employments = listOf(employment),
            selection = selection,
            catalog = DocumentCatalogSnapshot(checkpoint, selection, page),
            loading = false,
        )
    }
}
