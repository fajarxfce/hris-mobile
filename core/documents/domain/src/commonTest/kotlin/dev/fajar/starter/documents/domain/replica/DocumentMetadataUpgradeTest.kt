@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.documents.domain.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.documentFailure
import dev.fajar.starter.documents.domain.replica.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate

class DocumentMetadataUpgradeTest {
    @Test
    fun sameVersionUpgradeBypassesFreshnessAndRetainsPublishedFactsUntilCatchUp() = runTest {
        val f = CatalogSyncFixture()
        val old = previousFormat(f)
        supportMetadata(f)
        val gate = CompletableDeferred<Unit>()
        f.recordCall = { id ->
            gate.await()
            AppResult.Success(f.rows.getValue(id))
        }
        val syncing = async { f.sync("workspace", force = false) }
        runCurrent()
        assertEquals(
            DocumentCatalogCheckpoint.CURRENT_RECORD_SCHEMA,
            f.store.checkpoint?.recordSchema,
        )
        assertEquals(old, f.store.snapshot.value)
        val observed =
            assertIs<AppResult.Success<DocumentCatalogSnapshot>>(
                    f.observe("workspace", flowOf(f.selection)).first()
                )
                .value
        assertNull(observed.page.items.first().metadata)
        gate.complete(Unit)
        assertTrue(
            assertIs<AppResult.Success<DocumentCatalogSyncOutcome>>(syncing.await()).value.caughtUp
        )
        assertEquals(f.rows.values.toList(), f.store.snapshot.value.records)
        assertTrue(f.store.snapshot.value.records.all { it.version == 0L && it.metadataSupported })
        val reads = f.reads.size
        val bootstraps = f.bootstrapReads
        assertIs<AppResult.Success<DocumentCatalogSyncOutcome>>(f.sync("workspace", force = false))
        assertEquals(reads, f.reads.size)
        assertIs<AppResult.Success<DocumentCatalogSyncOutcome>>(f.sync("workspace"))
        assertEquals(bootstraps, f.bootstrapReads)
    }

    @Test
    fun failedOrCancelledUpgradePreservesOldFactsAndResumesItsGeneration() = runTest {
        for (cancelled in listOf(false, true)) {
            val f = CatalogSyncFixture()
            val old = previousFormat(f)
            supportMetadata(f)
            val gate = CompletableDeferred<Unit>()
            f.recordCall = { id ->
                withContext(NonCancellable) { gate.await() }
                if (cancelled) AppResult.Success(f.rows.getValue(id))
                else documentFailure("client.network", FailureKind.Network)
            }
            val updating = async { f.sync("workspace") }
            runCurrent()
            val generation = f.store.checkpoint?.generation
            if (cancelled) updating.cancel()
            gate.complete(Unit)
            if (cancelled) {
                updating.join()
                assertTrue(updating.isCancelled)
            } else assertIs<AppResult.Failed>(updating.await())
            assertEquals(old, f.store.snapshot.value)
            f.recordCall = { AppResult.Success(f.rows.getValue(it)) }
            assertTrue(
                assertIs<AppResult.Success<DocumentCatalogSyncOutcome>>(f.sync("workspace"))
                    .value
                    .caughtUp
            )
            assertEquals(generation, f.store.checkpoint?.generation)
            assertTrue(f.store.snapshot.value.records.all { it.metadataSupported })
        }
    }

    @Test
    fun serverCapabilityUpgradeRefreshesAnAlreadyCurrentClientWithoutNewDocumentVersions() =
        runTest {
            val f = CatalogSyncFixture()
            assertIs<AppResult.Success<DocumentCatalogSyncOutcome>>(f.sync("workspace"))
            val original = f.store.snapshot.value
            assertFalse(requireNotNull(original.checkpoint).metadataSupported)
            supportMetadata(f)
            assertIs<AppResult.Success<DocumentCatalogSyncOutcome>>(f.sync("workspace"))
            assertNotEquals(original.checkpoint.generation, f.store.checkpoint?.generation)
            assertEquals(2, f.bootstrapReads)
            assertTrue(
                f.store.snapshot.value.records.all { it.metadataSupported && it.version == 0L }
            )
        }

    @Test
    fun mixedCapabilityResponsesNeverPublishAPartialReplacement() = runTest {
        val f = CatalogSyncFixture()
        val old = previousFormat(f)
        supportMetadata(f)
        f.recordCall = { id ->
            AppResult.Success(
                f.rows
                    .getValue(id)
                    .copy(metadata = null, metadataVersion = null, metadataSupported = false)
            )
        }
        assertEquals(
            "client.invalid_response",
            assertIs<AppResult.Failed>(f.sync("workspace")).failure.code,
        )
        assertEquals(old, f.store.snapshot.value)
        assertNull(
            assertIs<AppResult.Success<DocumentCatalogReadAdmission>>(f.store.accessState.value)
                .value
                .denial
        )
    }

    @Test
    fun aConfirmedDenialDuringUpgradeStillClosesRetainedReadsWhenCleanupFails() = runTest {
        val f = CatalogSyncFixture()
        val old = previousFormat(f)
        supportMetadata(f)
        f.store.failedClear = true
        f.recordCall = { documentFailure("mfa_required", FailureKind.AccessDenied) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertEquals(old, f.store.snapshot.value)
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.observe("workspace", flowOf(f.selection)).first())
                .failure
                .code,
        )
        f.recordCall = { documentFailure("client.network", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertIs<AppResult.Failed>(f.observe("workspace", flowOf(f.selection)).first())
        f.recordCall = { AppResult.Success(f.rows.getValue(it)) }
        assertIs<AppResult.Success<DocumentCatalogSyncOutcome>>(f.sync("workspace"))
        assertTrue(
            assertIs<AppResult.Success<DocumentCatalogSnapshot>>(
                    f.observe("workspace", flowOf(f.selection)).first()
                )
                .value
                .page
                .items
                .all { it.metadataSupported }
        )
    }
}

private suspend fun previousFormat(f: CatalogSyncFixture): CatalogSyncFixture.Stored {
    assertIs<AppResult.Success<DocumentCatalogSyncOutcome>>(f.sync("workspace"))
    val old = requireNotNull(f.store.checkpoint).copy(recordSchema = 1)
    f.store.checkpoint = old
    f.store.snapshot.value = f.store.snapshot.value.copy(checkpoint = old)
    return f.store.snapshot.value
}

private fun supportMetadata(f: CatalogSyncFixture) {
    f.employmentCall = {
        AppResult.Success(
            DocumentEmploymentPage(
                listOf(f.employment),
                "2026-10-12",
                "Asia/Jakarta",
                null,
                metadataSupported = true,
            )
        )
    }
    f.rows.keys.toList().forEach { id ->
        f.rows[id] =
            f.rows
                .getValue(id)
                .copy(
                    metadataSupported = true,
                    metadataVersion = 0,
                    metadata =
                        DocumentBusinessMetadata(
                            DocumentCategory.PASSPORT,
                            "FIXTURE-IDENTITY",
                            "AU",
                            LocalDate(2010, 1, 1),
                            LocalDate(2020, 1, 1),
                        ),
                )
    }
}
