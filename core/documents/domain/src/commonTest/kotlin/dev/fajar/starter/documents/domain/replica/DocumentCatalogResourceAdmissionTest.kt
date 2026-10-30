@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.documents.domain.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.metadata.MetadataReadFixture
import dev.fajar.starter.documents.domain.policies.documentFailure
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.documents.domain.replica.usecases.ObserveDocumentCatalog
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class DocumentCatalogResourceAdmissionTest {
    @Test
    fun resourceDenialRedactsOnlyItsRecordAndPreservesPagingAndSubscriptionOwnership() = runTest {
        val f = CatalogSyncFixture()
        assertIs<AppResult.Success<*>>(f.sync("workspace"))
        val selection = MutableStateFlow(f.selection)
        val values = mutableListOf<AppResult<DocumentCatalogSnapshot>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe("workspace", selection).collect { values += it }
            }
        runCurrent()
        fun latest() = assertIs<AppResult.Success<DocumentCatalogSnapshot>>(values.last()).value
        val first = latest()
        assertEquals(20, first.page.items.size)
        assertEquals(20, f.resources.active)
        val denied = Failure(FailureKind.AccessDenied, "Denied", code = "document_access_denied")
        f.resources.restrict(f.access, catalogId(1), denied)
        runCurrent()
        assertEquals((2..20).map(::catalogId), latest().page.items.map { it.id })
        assertEquals(
            listOf(DocumentCatalogRestriction(catalogId(1), denied)),
            latest().restrictions,
        )
        assertEquals(first.page.nextCursor, latest().page.nextCursor)
        // Unrelated successful catalog acquisition cannot clear a known detail denial.
        assertIs<AppResult.Success<*>>(f.sync("workspace", true))
        runCurrent()
        assertEquals(19, latest().page.items.size)
        assertEquals(AppResult.Success(false), f.resources.admit(f.access, catalogId(1), null))
        runCurrent()
        assertEquals(19, latest().page.items.size)
        val gate = assertIs<AppResult.Success<*>>(f.resources.current(f.access, catalogId(1)))
        val revision =
            (gate.value
                    as
                    dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataReadAdmission)
                .revision
        assertEquals(AppResult.Success(true), f.resources.admit(f.access, catalogId(1), revision))
        runCurrent()
        assertEquals(first.page, latest().page)
        assertTrue(latest().restrictions.isEmpty())
        selection.value = f.selection.copy(after = catalogId(20))
        runCurrent()
        assertEquals((21..40).map(::catalogId), latest().page.items.map { it.id })
        assertEquals(20, f.resources.active)
        f.resources.restrict(f.access, catalogId(1), denied)
        runCurrent()
        assertTrue(latest().restrictions.isEmpty())
        reader.cancelAndJoin()
        assertEquals(0, f.resources.active)
        assertEquals(0, f.store.snapshot.subscriptionCount.value)
    }

    @Test
    fun anUnknownResourceGateFailsClosedAndCompanyDenialReleasesAllResourceReaders() = runTest {
        val f = CatalogSyncFixture()
        assertIs<AppResult.Success<*>>(f.sync("workspace"))
        val storage = Failure(FailureKind.Storage, "Unavailable", code = "client.storage")
        f.resources.fail(catalogId(2), storage)
        val values = mutableListOf<AppResult<DocumentCatalogSnapshot>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe("workspace", flowOf(f.selection)).collect { values += it }
            }
        runCurrent()
        val page = assertIs<AppResult.Success<DocumentCatalogSnapshot>>(values.last()).value
        assertEquals(19, page.page.items.size)
        assertFalse(page.page.items.any { it.id == catalogId(2) })
        assertEquals(listOf(DocumentCatalogRestriction(catalogId(2), storage)), page.restrictions)
        val denied = Failure(FailureKind.AccessDenied, "Verify", code = "mfa_required")
        f.store.restrict(f.access, denied)
        runCurrent()
        assertEquals(AppResult.Failed(denied), values.last())
        assertEquals(0, f.resources.active)
        assertEquals(0, f.store.snapshot.subscriptionCount.value)
        reader.cancelAndJoin()
    }

    @Test
    fun aRealDetailReadDenialStaysRedactedAcrossFailedAdmissionStorageAndRecovery() = runTest {
        val f = MetadataReadFixture()
        val c = f.catalog
        c.rows.clear()
        val row = c.record(1)
        c.rows[f.id] =
            row.copy(
                id = f.id,
                evidence = row.evidence.copy(documentId = f.id),
                metadata = f.metadata,
                metadataVersion = 0,
                metadataSupported = true,
            )
        assertIs<AppResult.Success<*>>(c.sync("workspace"))
        val observe =
            ObserveDocumentCatalog(
                c.store,
                f.admission,
                f.base.sessionRepository,
                f.base.workspaceRepository,
                f.base.clock,
                c.build,
            )
        val values = mutableListOf<AppResult<DocumentCatalogSnapshot>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observe("workspace", flowOf(c.selection)).collect { values += it }
            }
        runCurrent()
        fun latest() = assertIs<AppResult.Success<DocumentCatalogSnapshot>>(values.last()).value
        assertEquals(1, latest().page.items.size)
        f.admission.failStorage = true
        f.headCall = { documentFailure("document_not_found", FailureKind.Unexpected) }
        assertIs<AppResult.Failed>(f.refresh("workspace", f.id))
        runCurrent()
        assertTrue(latest().page.items.isEmpty())
        assertEquals("document_not_found", latest().restrictions.single().failure.code)
        f.headCall = { AppResult.Success(f.head) }
        assertIs<AppResult.Failed>(f.refresh("workspace", f.id))
        runCurrent()
        assertTrue(latest().page.items.isEmpty())
        f.admission.failStorage = false
        assertIs<AppResult.Success<*>>(f.refresh("workspace", f.id))
        runCurrent()
        assertEquals(1, latest().page.items.size)
        assertTrue(latest().restrictions.isEmpty())
        reader.cancelAndJoin()
        assertEquals(0, f.admission.state.subscriptionCount.value)
    }
}
