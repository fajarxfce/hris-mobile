package dev.fajar.starter.documents.domain.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.documentFailure
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.documents.domain.replica.policies.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class DocumentCatalogSyncTest {
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publishedStatusRetainsPreviousCompletionWhileStagingAndClosesOnDenial() = runTest {
        val f = CatalogSyncFixture()
        val observation: ObserveCompanyData = f.observe
        val acquisition: SyncCompanyData = f.sync
        val workspaceId = "workspace"
        assertEquals(CompanyDataSet.Documents, observation.dataSet)
        assertEquals(observation.dataSet, acquisition.dataSet)
        val values = mutableListOf<AppResult<CompanyDataStatus>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observation.observeStatus(workspaceId).collect { values += it }
            }
        runCurrent()
        assertEquals(AppResult.Success(CompanyDataStatus(null)), values.last())
        assertTrue(
            assertIs<AppResult.Success<CompanyDataSyncOutcome>>(acquisition.refresh(workspaceId))
                .value
                .caughtUp
        )
        runCurrent()
        val published = requireNotNull(f.store.checkpoint)
        val expected = AppResult.Success(CompanyDataStatus(assertNotNull(published.refreshedAt)))
        assertEquals(expected, values.last())
        val replacement =
            initialDocumentCatalogCheckpoint(
                published.access,
                "status-replacement",
                published.refreshedAt,
                published.employments,
            )
        assertEquals(AppResult.Success(true), f.store.begin(replacement, published.generation))
        runCurrent()
        assertEquals(expected, values.last())
        val denial =
            Failure(FailureKind.AccessDenied, "Verification required", code = "mfa_required")
        f.store.restrict(published.access, denial)
        runCurrent()
        assertEquals(AppResult.Failed(denial), values.last())
        assertEquals(AppResult.Failed(denial), observation.observeStatus(workspaceId).first())
        assertEquals(0, f.store.snapshot.subscriptionCount.value)
        reader.cancelAndJoin()
        assertEquals(0, f.store.snapshot.subscriptionCount.value)
    }

    @Test
    fun liveCredentialExpiryDoesNotWithdrawOwnedDocuments() = runTest {
        val f = CatalogSyncFixture()
        f.sync("workspace")
        f.policyCall = { documentFailure("session_expired", FailureKind.Unauthorized) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertNull((f.store.accessState.value as AppResult.Success).value.denial)
        assertNotNull(
            assertIs<AppResult.Success<DocumentCatalogSnapshot>>(
                    f.observe("workspace", flowOf(f.selection)).first()
                )
                .value
                .checkpoint
        )
    }

    @Test
    fun acquiredCatalogPagesAndCategoriesAreReadLocally() = runTest {
        val f = CatalogSyncFixture()
        f.rows[catalogId(45)] =
            f.rows.getValue(catalogId(45)).copy(classification = DocumentClassification.RECEIPT)
        assertTrue(
            assertIs<AppResult.Success<DocumentCatalogSyncOutcome>>(f.sync("workspace"))
                .value
                .caughtUp
        )
        val reads = f.reads.size
        val first =
            assertIs<AppResult.Success<DocumentCatalogSnapshot>>(
                    f.observe("workspace", flowOf(f.selection)).first()
                )
                .value
        assertEquals(20, first.page.items.size)
        val last =
            assertIs<AppResult.Success<DocumentCatalogSnapshot>>(
                    f.observe("workspace", flowOf(f.selection.copy(after = catalogId(40)))).first()
                )
                .value
        assertEquals((41..44).map(::catalogId), last.page.items.map { it.documentId })
        val receipts =
            assertIs<AppResult.Success<DocumentCatalogSnapshot>>(
                    f.observe(
                            "workspace",
                            flowOf(
                                f.selection.copy(classification = DocumentClassification.RECEIPT)
                            ),
                        )
                        .first()
                )
                .value
        assertEquals(listOf(catalogId(45)), receipts.page.items.map { it.documentId })
        assertEquals(reads, f.reads.size)
        assertEquals(1, f.bootstrapReads)
        assertEquals(1, f.changeReads)
        assertEquals(1, f.employmentReads)
        assertEquals(0, f.base.evidenceReads)
    }

    @Test
    fun ordinaryAgeAndCredentialExpiryNeverHideOwnedLocalFacts() = runTest {
        val f = CatalogSyncFixture()
        f.sync("workspace")
        f.now += 73.hours
        val expired =
            f.base.session.copy(
                binding =
                    f.base.session.binding.copy(
                        expiresAtEpochMillis = (f.now - 72.hours).toEpochMilliseconds()
                    )
            )
        f.base.sessions.value = AppResult.Success(expired)
        val view =
            assertIs<AppResult.Success<DocumentCatalogSnapshot>>(
                    f.observe("workspace", flowOf(f.selection.copy(after = catalogId(44)))).first()
                )
                .value
        assertEquals("Document 45", view.page.items.single().title)
        assertEquals(1, f.bootstrapReads)
        assertEquals(1, f.changeReads)
    }

    @Test
    fun routeEntrySkipsFreshAcquisitionWhileExplicitRefreshStillSynchronizes() = runTest {
        val f = CatalogSyncFixture()
        f.sync("workspace")
        f.sync("workspace", force = false)
        assertEquals(1, f.changeReads)
        f.sync("workspace")
        assertEquals(2, f.changeReads)
    }

    @Test
    fun publicationPendingNeverExposesAnIncompleteReplacement() = runTest {
        val f = CatalogSyncFixture()
        f.changesCall = {
            AppResult.Success(SyncChangePage(emptyList(), "head", false, true, 5, f.now))
        }
        assertFalse(
            assertIs<AppResult.Success<DocumentCatalogSyncOutcome>>(f.sync("workspace"))
                .value
                .caughtUp
        )
        assertNull(f.store.snapshot.value.checkpoint)
        assertTrue(f.changeReads <= 10)
        f.changesCall = {
            AppResult.Success(SyncChangePage(emptyList(), "head", false, false, 5, f.now))
        }
        assertTrue(
            assertIs<AppResult.Success<DocumentCatalogSyncOutcome>>(f.sync("workspace"))
                .value
                .caughtUp
        )
        assertNotNull(f.store.snapshot.value.checkpoint)
    }

    @Test
    fun withdrawnRevisionsDoNotRevokeTheCatalog() = runTest {
        val f = CatalogSyncFixture()
        f.sync("workspace")
        f.rows.remove(catalogId(1))
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    listOf(
                        SyncChange(
                            SyncReference(SyncCollection.DOCUMENT_EVIDENCE, catalogId(1), 1),
                            SyncOperation.UPSERT,
                        ),
                        SyncChange(
                            SyncReference(SyncCollection.DOCUMENT_EVIDENCE, catalogId(2), 1),
                            SyncOperation.DELETE,
                        ),
                    ),
                    "head-2",
                    false,
                    false,
                    5,
                    f.now,
                )
            )
        }
        f.sync("workspace")
        assertEquals(43, f.store.snapshot.value.records.size)
        assertNull((f.store.accessState.value as AppResult.Success).value.denial)
        assertEquals(1, f.reads.count { it == catalogId(2) })
    }

    @Test
    fun duplicateReferencesPreferTheirLatestVersionAndDeletion() = runTest {
        val f = CatalogSyncFixture()
        f.sync("workspace")
        val reads = f.reads.size
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    listOf(
                        SyncChange(
                            SyncReference(SyncCollection.DOCUMENT_EVIDENCE, catalogId(1), 2),
                            SyncOperation.UPSERT,
                        ),
                        SyncChange(
                            SyncReference(SyncCollection.DOCUMENT_EVIDENCE, catalogId(1), 2),
                            SyncOperation.DELETE,
                        ),
                        SyncChange(
                            SyncReference(SyncCollection.DOCUMENT_EVIDENCE, catalogId(1), 1),
                            SyncOperation.UPSERT,
                        ),
                    ),
                    "head-2",
                    false,
                    false,
                    5,
                    f.now,
                )
            )
        }
        f.sync("workspace")
        assertEquals(reads, f.reads.size)
        assertFalse(f.store.snapshot.value.records.any { it.id == catalogId(1) })
    }

    @Test
    fun cancellationDuringHydrationCannotAdvanceTheCursor() = runTest {
        val f = CatalogSyncFixture()
        val entered = CompletableDeferred<Unit>()
        var active = 0
        f.recordCall = {
            entered.complete(Unit)
            active++
            try {
                awaitCancellation()
            } finally {
                active--
            }
        }
        val job = launch { f.sync("workspace") }
        entered.await()
        assertTrue(active in 1..4)
        job.cancelAndJoin()
        assertEquals(0, active)
        assertEquals(0, f.store.checkpoint!!.revision)
        assertNull(f.store.snapshot.value.checkpoint)
    }

    @Test
    fun scopeReplacementRejectsLateResults() = runTest {
        val f = CatalogSyncFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.recordCall = { id ->
            entered.complete(Unit)
            release.await()
            AppResult.Success(f.rows.getValue(id))
        }
        val job = async { f.sync("workspace") }
        entered.await()
        f.base.workspaces.value = AppResult.Success(f.workspace.copy(id = "replacement"))
        release.complete(Unit)
        assertIs<AppResult.Failed>(job.await())
        assertNull(f.store.snapshot.value.checkpoint)
    }

    @Test
    fun oneFailedRecordCancelsItsPendingSiblingsAndDoesNotPublish() = runTest {
        val f = CatalogSyncFixture()
        val blocked = CompletableDeferred<Unit>()
        var active = 0
        f.recordCall = { id ->
            if (id == catalogId(1)) {
                blocked.await()
                documentFailure("unavailable", FailureKind.Network)
            } else {
                active++
                blocked.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    active--
                }
            }
        }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertEquals(0, active)
        assertEquals(0, f.store.checkpoint!!.revision)
    }

    @Test
    fun failedCleanupAndOfflineRetriesCannotReopenAConfirmedDenial() = runTest {
        val f = CatalogSyncFixture()
        f.sync("workspace")
        f.store.failedClear = true
        f.policyCall = { documentFailure("mfa_required", FailureKind.AccessDenied) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        f.policyCall = { documentFailure("unavailable", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        val denied = assertIs<AppResult.Failed>(f.observe("workspace", flowOf(f.selection)).first())
        assertEquals("mfa_required", denied.failure.code)
        f.policyCall = {
            AppResult.Success(
                f.workspace.policy!!.policy.copy(serverTime = f.now, validUntil = f.now + 1.hours)
            )
        }
        assertTrue(
            assertIs<AppResult.Success<DocumentCatalogSyncOutcome>>(f.sync("workspace"))
                .value
                .caughtUp
        )
        assertNotNull(
            assertIs<AppResult.Success<DocumentCatalogSnapshot>>(
                    f.observe("workspace", flowOf(f.selection)).first()
                )
                .value
                .checkpoint
        )
    }

    @Test
    fun expiredCursorKeepsPublishedFactsDuringAnUnsuccessfulReplacement() = runTest {
        val f = CatalogSyncFixture()
        f.sync("workspace")
        val old = f.store.snapshot.value
        f.changesCall = { documentFailure("sync_cursor_expired", FailureKind.Conflict) }
        f.bootstrapCall = { documentFailure("unavailable", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertEquals(old, f.store.snapshot.value)
        assertNull((f.store.accessState.value as AppResult.Success).value.denial)
    }

    @Test
    fun canonicalIdentityVersionAndEmploymentMustMatchTheFeedScope() = runTest {
        for (variant in 0..2) {
            val f = CatalogSyncFixture()
            f.recordCall = { id ->
                val row = f.rows.getValue(id)
                AppResult.Success(
                    when (variant) {
                        0 -> row.copy(id = catalogId(999))
                        1 -> row.copy(version = -1)
                        else -> row.copy(employmentId = catalogId(999))
                    }
                )
            }
            assertIs<AppResult.Failed>(f.sync("workspace"))
            assertNull(f.store.snapshot.value.checkpoint)
            assertEquals(0, f.store.checkpoint!!.revision)
        }
    }

    @Test
    fun employmentPaginationCannotRepeatOrAccumulateDuplicates() = runTest {
        val f = CatalogSyncFixture()
        f.employmentCall = {
            AppResult.Success(
                DocumentEmploymentPage(
                    listOf(f.employment),
                    "2026-10-12",
                    "Asia/Jakarta",
                    "EMP-001",
                )
            )
        }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertEquals(2, f.employmentReads)
        assertEquals(0, f.bootstrapReads)
        assertTrue(f.reads.isEmpty())
    }

    @Test
    fun lateCancelledSuccessCannotCommitOrReopenReadAdmission() = runTest {
        val f = CatalogSyncFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.recordCall = { id ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(f.rows.getValue(id))
        }
        val operation = async { f.sync("workspace") }
        entered.await()
        operation.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { operation.await() }
        operation.join()
        assertEquals(0, f.store.checkpoint!!.revision)
        assertNull(f.store.snapshot.value.checkpoint)
    }

    @Test
    fun malformedSelectionAndCollectionNeverAcquireOrPublish() = runTest {
        val f = CatalogSyncFixture()
        assertIs<AppResult.Failed>(
            validateDocumentCatalogSelection(DocumentCatalogSelection(employmentId = "not-an-id"))
        )
        f.bootstrapCall = {
            AppResult.Success(
                SyncBootstrapPage(setOf(SyncCollection.PAYSLIPS), emptyList(), null, "head", f.now)
            )
        }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertTrue(f.reads.isEmpty())
        assertNull(f.store.snapshot.value.checkpoint)
    }
}
