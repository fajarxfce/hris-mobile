@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.documents.domain.metadata.working

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.working.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class DocumentMetadataWorkingFlowTest {
    @Test
    fun ownedDraftReopensWithoutRebasingOrRequiringTheBinaryCatalog() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened()
        val reads = f.reads.headReads
        f.reads.cache.stored.value =
            assertNotNull(f.reads.cache.stored.value).copy(head = original.head.copy(version = 4))
        assertEquals(AppResult.Success(original), f.open(f.command.workspaceId, original.head.id))
        f.reads.cache.stored.value = null
        f.base.now += 365.days
        assertEquals(AppResult.Success(original), f.open(f.command.workspaceId, original.head.id))
        assertEquals(1, f.copies.saves)
        assertEquals(reads, f.reads.headReads)
        assertTrue(f.command.sent.isEmpty())
    }

    @Test
    fun aLostSaveAcknowledgementRecoversExactlyAndCannotOverwriteNewerInput() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened()
        val next =
            original.copy(
                revision = 1,
                input = original.input.copy(issuedOn = "2026-"),
                reason = "Unfinished\nreason",
            )
        f.copies.loseSaveAcknowledgement = true
        assertIs<AppResult.Failed>(f.save(f.command.workspaceId, original, next))
        assertEquals(next, f.copies.records.value[original.head.id])
        f.copies.loseSaveAcknowledgement = false
        val calls = f.copies.saves
        assertEquals(AppResult.Success(next), f.save(f.command.workspaceId, original, next))
        assertEquals(calls, f.copies.saves)
        val newer = next.copy(revision = 2, reason = "Newer user input")
        assertEquals(AppResult.Success(newer), f.save(f.command.workspaceId, next, newer))
        assertEquals(
            "client.document_working_copy_changed",
            assertIs<AppResult.Failed>(f.save(f.command.workspaceId, original, next)).failure.code,
        )
        assertEquals(newer, f.copies.records.value[original.head.id])
    }

    @Test
    fun explicitDiscardRecoversLostAcknowledgementAndPreservesAConcurrentReplacement() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened()
        val newer = original.copy(revision = 1, reason = "Newer input")
        f.copies.beforeRemove = { f.copies.records.value = mapOf(original.head.id to newer) }
        assertIs<AppResult.Failed>(f.discard(f.command.workspaceId, original))
        assertEquals(newer, f.copies.records.value[original.head.id])
        f.copies.beforeRemove = {}
        f.copies.loseRemoveAcknowledgement = true
        assertIs<AppResult.Failed>(f.discard(f.command.workspaceId, newer))
        f.copies.loseRemoveAcknowledgement = false
        assertEquals(AppResult.Success(Unit), f.discard(f.command.workspaceId, newer))
        assertTrue(f.copies.records.value.isEmpty())
    }

    @Test
    fun deniedDraftStopsItsStorageSubscriptionEvenWhenStorageCannotRecover() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened()
        val values = mutableListOf<AppResult<DocumentMetadataWorkingView>>()
        val observer =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.command.workspaceId, original.head.id).toList(values)
            }
        runCurrent()
        assertEquals(
            listOf(original),
            assertIs<AppResult.Success<DocumentMetadataWorkingView>>(values.last()).value.page.items,
        )
        f.copies.observationFailure = Failure(FailureKind.Storage, "Unavailable")
        f.reads.admission.failStorage = true
        f.reads.admission.restrict(
            f.command.access,
            original.head.id,
            Failure(FailureKind.AccessDenied, "Denied", code = "document_access_denied"),
        )
        runCurrent()
        val denied = assertIs<AppResult.Success<DocumentMetadataWorkingView>>(values.last()).value
        assertTrue(denied.page.items.isEmpty())
        assertEquals("document_access_denied", denied.restrictions.single().code)
        assertEquals(0, f.copies.active)
        assertIs<AppResult.Failed>(f.open(f.command.workspaceId, original.head.id))
        assertIs<AppResult.Failed>(
            f.save(f.command.workspaceId, original, original.copy(revision = 1, reason = "Changed"))
        )
        assertEquals(1, f.copies.observations)
        f.reads.admission.failStorage = false
        assertIs<AppResult.Success<Unit>>(f.reads.refresh(f.command.workspaceId, original.head.id))
        runCurrent()
        assertIs<AppResult.Failed>(values.last())
        f.copies.observationFailure = null
        observer.cancelAndJoin()
        assertEquals(
            listOf(original),
            assertIs<AppResult.Success<DocumentMetadataWorkingView>>(
                    f.observe(f.command.workspaceId, original.head.id).first()
                )
                .value
                .page
                .items,
        )
        assertEquals(0, f.copies.active)
    }

    @Test
    fun lateAccountChangeCannotReturnOrCreateAnotherOwnersDraft() = runTest {
        val f = MetadataWorkingFixture()
        f.command.view()
        f.copies.beforeCurrent = {
            f.base.sessions.value = AppResult.Success(f.base.session.copy(id = "replacement"))
        }
        assertIs<AppResult.Failed>(f.open(f.command.workspaceId, f.reads.id))
        assertTrue(f.copies.records.value.isEmpty())
        assertNull(f.reads.admission.state.value.denial)
    }

    @Test
    fun readOnlyAccessCanKeepAndDiscardOwnedInputWithoutMakingNewEdits() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened()
        f.base.workspaces.value = AppResult.Success(f.base.workspace)
        assertEquals(AppResult.Success(original), f.open(f.command.workspaceId, original.head.id))
        assertIs<AppResult.Failed>(
            f.save(
                f.command.workspaceId,
                original,
                original.copy(revision = 1, reason = "New edit"),
            )
        )
        assertEquals(AppResult.Success(Unit), f.discard(f.command.workspaceId, original))
        assertIs<AppResult.Failed>(f.open(f.command.workspaceId, original.head.id))
        assertTrue(f.command.sent.isEmpty())
    }

    @Test
    fun cancelledAndTimedOutLocalWritesPreserveDraftAndReleaseMutex() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened()
        val next = original.copy(revision = 1, reason = "New edit")
        val entered = CompletableDeferred<Unit>()
        f.copies.beforeSave = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.save(f.command.workspaceId, original, next) }
        entered.await()
        task.cancelAndJoin()
        assertEquals(original, f.copies.records.value[original.head.id])
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.save(f.command.workspaceId, original, next)).failure.kind,
        )
        assertFalse(f.mutex.isLocked)
        f.copies.beforeSave = {}
        assertEquals(AppResult.Success(next), f.save(f.command.workspaceId, original, next))
    }

    @Test
    fun acknowledgementCleansOnlyTheMatchingDraftAndKeepsReceiptIfCleanupFails() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened()
        val matching =
            original.copy(revision = 1, input = f.command.input, reason = " User correction ")
        f.save(f.command.workspaceId, original, matching)
        val pending = f.command.prepared()
        val confirmed =
            assertIs<AppResult.Success<DocumentMetadataCommandRecord>>(
                    f.command.replay()(f.command.workspaceId, pending.command.change.operationId)
                )
                .value
        advanceHeader(f, confirmed)
        f.copies.removeFailure = true
        assertIs<AppResult.Failed>(f.command.review(f.command.workspaceId, confirmed))
        assertEquals(confirmed, f.command.record())
        assertEquals(matching, f.copies.records.value[original.head.id])
        f.copies.removeFailure = false
        assertEquals(AppResult.Success(true), f.command.review(f.command.workspaceId, confirmed))
        assertTrue(f.copies.records.value.isEmpty())
        assertTrue(f.command.commands.records.value.isEmpty())
        assertEquals(1, f.command.sent.size)
    }

    @Test
    fun acknowledgementPreservesChangedLaterOrRebasedDrafts() = runTest {
        for (variant in 0..2) {
            val f = MetadataWorkingFixture()
            val original = f.opened()
            val pending = f.command.prepared()
            val confirmed =
                assertIs<AppResult.Success<DocumentMetadataCommandRecord>>(
                        f.command.replay()(
                            f.command.workspaceId,
                            pending.command.change.operationId,
                        )
                    )
                    .value
            advanceHeader(f, confirmed)
            val matching =
                original.copy(revision = 1, input = f.command.input, reason = "User correction")
            val retained =
                when (variant) {
                    0 -> matching.copy(reason = "A different edit")
                    1 -> matching.copy(updatedAt = pending.command.createdAt + 1.seconds)
                    else -> matching.copy(head = matching.head.copy(version = 4))
                }
            f.copies.records.value = mapOf(original.head.id to retained)
            assertEquals(
                AppResult.Success(true),
                f.command.review(f.command.workspaceId, confirmed),
            )
            assertEquals(retained, f.copies.records.value[original.head.id])
        }
    }

    @Test
    fun concurrentDraftEditWinsOverAcknowledgementCleanup() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened()
        val matching =
            original.copy(revision = 1, input = f.command.input, reason = "User correction")
        f.copies.records.value = mapOf(original.head.id to matching)
        val pending = f.command.prepared()
        val confirmed =
            assertIs<AppResult.Success<DocumentMetadataCommandRecord>>(
                    f.command.replay()(f.command.workspaceId, pending.command.change.operationId)
                )
                .value
        advanceHeader(f, confirmed)
        val later = matching.copy(revision = 2, reason = "Later change")
        f.copies.beforeRemove = { f.copies.records.value = mapOf(original.head.id to later) }
        assertEquals(AppResult.Success(true), f.command.review(f.command.workspaceId, confirmed))
        assertEquals(later, f.copies.records.value[original.head.id])
        assertTrue(f.command.commands.records.value.isEmpty())
    }

    private fun advanceHeader(f: MetadataWorkingFixture, confirmed: DocumentMetadataCommandRecord) {
        val checkpoint = assertNotNull(f.reads.cache.stored.value)
        f.reads.cache.stored.value =
            checkpoint.copy(
                head =
                    checkpoint.head.copy(
                        version =
                            (confirmed.outcome as DocumentMetadataCommandOutcome.Confirmed).version,
                        metadataVersion = 3,
                        metadata = confirmed.command.change.metadata,
                    )
            )
    }
}
