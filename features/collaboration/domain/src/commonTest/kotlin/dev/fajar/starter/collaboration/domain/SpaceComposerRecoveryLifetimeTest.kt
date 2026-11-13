@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceComposerIntent
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SpaceComposerRecoveryLifetimeTest {
    @Test
    fun accountReplacementDuringRootReadCannotReadTheOldOutbox() = runTest {
        val f = SpaceComposerRecoveryFixture()
        f.retain(1)
        f.beforeRoot = { f.base.identity.value = AppResult.Success(null) }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.recover()()).failure.kind,
        )
        assertEquals(0, f.cursorReads + f.outboxReads + f.messageReads)
        assertTrue(f.inserted.isEmpty())
    }

    @Test
    fun confirmedDenialAndAccountLossDuringAdmissionBlockPrivateReads() = runTest {
        for (denied in listOf(false, true)) {
            val f = SpaceComposerRecoveryFixture()
            f.retain(1)
            if (denied) f.base.base.deny()
            else f.base.base.beforeAdmission = { f.base.identity.value = AppResult.Success(null) }
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.recover()()).failure.kind,
            )
            assertEquals(0, f.cursorReads + f.outboxReads + f.messageReads)
        }
    }

    @Test
    fun malformedRetainedCursorCannotStartAnotherPage() = runTest {
        for (badGeneration in listOf(false, true)) {
            val f = SpaceComposerRecoveryFixture()
            f.retain(1)
            f.cursor =
                CollaborationComposerRecoveryCursor(
                    if (badGeneration) "invalid" else deliveryId(1),
                    if (badGeneration) deliveryId(2) else "invalid",
                )
            assertIs<AppResult.Failed>(f.recover()())
            assertEquals(0, f.cursorWrites + f.outboxReads + f.messageReads)
            assertTrue(f.positions.isEmpty())
        }
    }

    @Test
    fun malformedIndexedPagesCannotAdvanceOrMaterializeAnyOriginal() = runTest {
        for (variant in 0..4) {
            val f = SpaceComposerRecoveryFixture()
            val originals = f.retain(9).sortedBy { it.id }
            val page =
                when (variant) {
                    0 ->
                        CollaborationComposerSubmissionPage(
                            listOf(originals[0], originals[0]),
                            null,
                        )
                    1 -> CollaborationComposerSubmissionPage(originals.take(2).reversed(), null)
                    2 -> CollaborationComposerSubmissionPage(originals, null)
                    3 -> CollaborationComposerSubmissionPage(originals.take(2), originals[1].id)
                    else ->
                        CollaborationComposerSubmissionPage(
                            listOf(
                                originals[0].copy(original = originals[0].original.copy(text = " "))
                            ),
                            null,
                        )
                }
            f.pendingOverride = AppResult.Success(page)
            assertIs<AppResult.Failed>(f.recover()())
            assertEquals(0, f.cursorWrites + f.outboxReads + f.messageReads)
            assertTrue(f.inserted.isEmpty() && f.retired.isEmpty())
        }
    }

    @Test
    fun lateDenialOrSignOutPreservesTheCommittedCommandAndUnretiredOriginal() = runTest {
        for (denied in listOf(false, true)) {
            val f = SpaceComposerRecoveryFixture()
            val original = f.retain(1).single()
            f.onInsert = {
                f.records[it.id] =
                    CollaborationSpaceMessageRecord(it, 0, CollaborationMessageState.Queued)
                if (denied) f.base.base.deny() else f.base.identity.value = AppResult.Success(null)
                AppResult.Success(CollaborationMessageInsertion.STORED)
            }
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.recover()()).failure.kind,
            )
            assertEquals(original, f.retained[original.id])
            assertEquals(original.submittedAt, f.records[original.id]!!.intent.createdAt)
            assertTrue(f.retired.isEmpty())
        }
    }

    @Test
    fun cancellationAfterInsertionPropagatesAndRetryRetiresOnlyTheSameOriginal() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.retain(1).single()
        val committed = CompletableDeferred<Unit>()
        f.onInsert = {
            f.records[it.id] =
                CollaborationSpaceMessageRecord(it, 0, CollaborationMessageState.Queued)
            committed.complete(Unit)
            awaitCancellation()
        }
        val work = async { f.recover()() }
        committed.await()
        work.cancelAndJoin()
        assertTrue(work.isCancelled)
        assertEquals(original, f.retained[original.id])
        assertTrue(f.retired.isEmpty())
        f.onInsert = { error("Retry must use the retained command") }
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, true)),
            f.recover()(),
        )
        assertEquals(listOf(original), f.retired)
        assertEquals(1, f.inserted.size)
    }

    @Test
    fun failedReadbackCannotAuthorizeRetirementAndLaterRecoveryUsesTheSameCommand() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.retain(1).single()
        f.onInsert = {
            f.records[it.id] =
                CollaborationSpaceMessageRecord(it, 0, CollaborationMessageState.Queued)
            f.readFailure = f.base.base.storageFailure
            AppResult.Success(CollaborationMessageInsertion.STORED)
        }
        assertEquals(AppResult.Failed(f.base.base.storageFailure), f.recover()())
        assertEquals(original, f.retained[original.id])
        assertTrue(f.retired.isEmpty())
        f.readFailure = null
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, true)),
            f.recover()(),
        )
        assertEquals(listOf(original), f.retired)
        assertEquals(1, f.inserted.size)
    }

    @Test
    fun stalledLocalReadTimesOutWithoutRetryOrContentMutation() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.retain(1).single()
        f.beforeCursor = { awaitCancellation() }
        val before = testScheduler.currentTime
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.recover()()).failure.kind)
        assertEquals(5000L, testScheduler.currentTime - before)
        assertEquals(1, f.cursorReads)
        assertEquals(original, f.retained[original.id])
        assertTrue(f.inserted.isEmpty() && f.retired.isEmpty() && f.positions.isEmpty())
    }

    @Test
    fun renewedLoginAndMissingMetadataDoNotInvalidateAnExactRetainedCommand() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.retain(1).single()
        val record =
            CollaborationSpaceMessageRecord(
                collaborationSpaceComposerIntent(f.access, original),
                0,
                CollaborationMessageState.Queued,
            )
        f.records[original.id] = record
        f.base.canonical = null
        f.base.native.setup.now += 100.days
        f.base.identity.value = AppResult.Success(f.base.native.session.copy(id = "renewed"))
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, true)),
            f.recover()(),
        )
        assertEquals(listOf(original), f.retired)
        assertEquals(record, f.records[original.id])
        assertEquals(0, f.metadataReads)
        assertTrue(f.inserted.isEmpty())
    }

    @Test
    fun competingRetirementIsConfirmedByAbsenceWithoutAnotherInsertion() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.retain(1).single()
        f.onRemove = {
            f.retained.remove(it.id)
            AppResult.Success(false)
        }
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, true)),
            f.recover()(),
        )
        assertEquals(listOf(original.id), f.inserted.map { it.id })
        assertEquals(listOf(original), f.retired)
        assertTrue(f.retained.isEmpty())
    }
}
