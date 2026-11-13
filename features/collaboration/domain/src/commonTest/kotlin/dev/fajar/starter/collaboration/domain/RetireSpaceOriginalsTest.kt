package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RetireSpaceOriginalsTest {
    @Test
    fun historyProvedOriginalArchivesWithDemandBeforeItsReceiptIsRemoved() = runTest {
        val f = SpaceOriginalRetirementFixture()
        val pass = CollaborationSpaceInboxRepairPass(f.repair.initial.latestDemand, "saved-page")
        f.repair.stored = f.repair.initial.copy(pass = pass)
        assertEquals(AppResult.Success(1), f.retire()(f.access, f.conversation))
        assertTrue(f.journal.isEmpty())
        assertEquals(f.first, f.archives.getValue(f.first.intent.id).record)
        assertEquals(pass, f.repair.stored?.pass)
        assertEquals(1, f.publications.size)
        assertTrue(f.operations.indexOf("archive") < f.operations.indexOf("remove"))
        assertTrue(f.repair.replacements.isEmpty())
        assertEquals(0, f.base.native.pageCalls)
    }

    @Test
    fun archivedReplayCleansItsReceiptWithoutAcquiringOrRewritingProgress() = runTest {
        val f = SpaceOriginalRetirementFixture()
        f.archives[f.first.intent.id] =
            CollaborationSpaceMessageCompletion(f.first, f.journal.single().eventIds)
        f.records.clear()
        f.repair.onCheckpoint = { error("Archived replay must not acquire progress") }
        assertEquals(AppResult.Success(1), f.retire()(f.access, f.conversation))
        assertTrue(f.publications.isEmpty())
        assertEquals(f.repair.initial, f.repair.stored)
        assertEquals(AppResult.Success(0), f.retire()(f.access, f.conversation))
    }

    @Test
    fun lostArchiveResponseStopsAndExplicitRetryConfirmsItsImmutableProof() = runTest {
        val f = SpaceOriginalRetirementFixture()
        val commit = f.onArchive
        f.onArchive = { completion, publication ->
            assertEquals(AppResult.Success(true), commit(completion, publication))
            AppResult.Failed(f.base.storageFailure)
        }
        assertEquals(AppResult.Failed(f.base.storageFailure), f.retire()(f.access, f.conversation))
        assertTrue(f.removals.isEmpty())
        val retained = f.repair.stored
        f.repair.onCheckpoint = { error("Retry must confirm the archive") }
        assertEquals(AppResult.Success(1), f.retire()(f.access, f.conversation))
        assertEquals(1, f.publications.size)
        assertEquals(retained, f.repair.stored)
    }

    @Test
    fun partialArchivalPreservesEverySelectedReceiptUntilExplicitRecovery() = runTest {
        val f = SpaceOriginalRetirementFixture()
        f.addOriginal(2)
        val commit = f.onArchive
        f.onArchive = { completion, publication ->
            if (f.publications.size == 2) AppResult.Failed(f.base.storageFailure)
            else commit(completion, publication)
        }
        assertEquals(AppResult.Failed(f.base.storageFailure), f.retire()(f.access, f.conversation))
        assertEquals(1, f.archives.size)
        assertEquals(2, f.journal.size)
        assertTrue(f.removals.isEmpty())
        assertEquals(AppResult.Success(2), f.retire()(f.access, f.conversation))
        assertEquals(3, f.publications.size)
        assertTrue(f.journal.isEmpty())
    }

    @Test
    fun cleanupFailureDoesNotRepeatArchivesOnALaterInvocation() = runTest {
        val f = SpaceOriginalRetirementFixture()
        f.readFailure = "remove"
        assertEquals(AppResult.Failed(f.base.storageFailure), f.retire()(f.access, f.conversation))
        assertEquals(1, f.journal.size)
        val retained = f.repair.stored
        f.readFailure = null
        assertEquals(AppResult.Success(1), f.retire()(f.access, f.conversation))
        assertEquals(1, f.publications.size)
        assertEquals(retained, f.repair.stored)
    }

    @Test
    fun concurrentCleanupIsConfirmedByOneBoundedLedgerRead() = runTest {
        for (confirmationFails in listOf(false, true)) {
            val f = SpaceOriginalRetirementFixture()
            f.onRemove = {
                f.journal.clear()
                if (confirmationFails) f.readFailure = "journal"
                AppResult.Success(false)
            }
            val result = f.retire()(f.access, f.conversation)
            if (confirmationFails) assertEquals(AppResult.Failed(f.base.storageFailure), result)
            else assertEquals(AppResult.Success(1), result)
            assertEquals(2, f.operations.count { it == "journal" })
            assertEquals(1, f.removals.size)
            f.readFailure = null
            assertEquals(AppResult.Success(0), f.retire()(f.access, f.conversation))
        }
    }

    @Test
    fun aNewAliasSurvivesStaleCleanupAndRequiresHistoryBeforeTheNextRetirement() = runTest {
        val f = SpaceOriginalRetirementFixture()
        val remove = f.onRemove
        val later = f.journal.single().copy(eventIds = listOf("\$later"))
        f.onRemove = {
            f.journal[0] = later
            AppResult.Success(false)
        }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.retire()(f.access, f.conversation)).failure.kind,
        )
        val archive = f.archives.getValue(f.first.intent.id)
        val retained = f.repair.stored
        assertEquals(listOf(later), f.journal)
        f.onRemove = remove
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.retire()(f.access, f.conversation)).failure.kind,
        )
        f.originals[f.first.intent.id] =
            assertNotNull(
                mergeCollaborationHistoryText(
                    f.originals.getValue(f.first.intent.id),
                    CollaborationHistoryChange.Text(
                        "\$later",
                        later.origin.sender,
                        f.first.intent.id,
                        101,
                        f.first.intent.text,
                        later.origin.contentHash,
                        replyToEventId = f.first.intent.replyToEventId,
                        forwarded = f.first.intent.forwarded,
                    ),
                )
            )
        assertEquals(AppResult.Success(1), f.retire()(f.access, f.conversation))
        assertEquals(archive, f.archives.getValue(f.first.intent.id))
        assertEquals(retained, f.repair.stored)
        assertEquals(1, f.publications.size)
    }

    @Test
    fun oneInvocationRetiresAtMostThirtyTwoProvedOriginals() = runTest {
        val f = SpaceOriginalRetirementFixture()
        for (number in 2..33) f.addOriginal(number)
        assertEquals(AppResult.Success(32), f.retire()(f.access, f.conversation))
        assertEquals(32, f.removals.single().size)
        assertEquals(1, f.journal.size)
        assertEquals(AppResult.Success(1), f.retire()(f.access, f.conversation))
        assertEquals(33, f.archives.size)
    }

    @Test
    fun missingHistoryUnsettledCommandsAndEditsDoNotStarveLaterProvedOriginals() = runTest {
        for (kind in 0..2) {
            val f = SpaceOriginalRetirementFixture()
            when (kind) {
                0 -> f.originals.clear()
                1 -> {
                    val sent = f.first.state as CollaborationMessageState.Sent
                    f.records[f.first.intent.id] =
                        f.first.copy(
                            revision = 2,
                            state =
                                CollaborationMessageState.Staged(
                                    sent.device,
                                    sent.transactionId,
                                    sent.origin,
                                ),
                        )
                }
                2 -> {
                    val sent = f.first.state as CollaborationMessageState.Sent
                    val target =
                        CollaborationEditTarget(
                            sent.device.roomId,
                            "\$edited",
                            f.repair.id(999),
                            requireNotNull(sent.origin).sender,
                            "b".repeat(64),
                            1,
                            1,
                            f.first.intent.replyToEventId,
                            f.first.intent.forwarded,
                        )
                    val edited = f.first.copy(intent = f.first.intent.copy(editTarget = target))
                    assertTrue(validCollaborationSpaceMessageRecord(f.access, edited))
                    f.records[f.first.intent.id] = edited
                    f.journal[0] = f.journal[0].copy(intent = edited.intent)
                }
            }
            f.addOriginal(2)
            assertEquals(AppResult.Success(1), f.retire()(f.access, f.conversation))
            assertEquals(f.first.intent.id, f.journal.single().intent.id)
            assertEquals(1, f.archives.size)
        }
    }

    @Test
    fun threeArchiveConflictsStopWithoutCleaningOrMutatingProgressSeparately() = runTest {
        val f = SpaceOriginalRetirementFixture()
        f.archiveConflicts = 3
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.retire()(f.access, f.conversation)).failure.kind,
        )
        assertEquals(3, f.publications.size)
        assertEquals(1, f.publications.map { it.second.replacement.latestDemand }.toSet().size)
        assertEquals(f.repair.initial, f.repair.stored)
        assertTrue(f.removals.isEmpty())
        assertTrue(f.repair.replacements.isEmpty())
    }

    @Test
    fun checkpointContentionPreservesTheLatestCapturedPassOnTheNextAttempt() = runTest {
        val f = SpaceOriginalRetirementFixture()
        val next =
            f.repair.initial.copy(
                generation = f.repair.id(40),
                latestDemand = f.repair.id(41),
                pass = CollaborationSpaceInboxRepairPass(f.repair.initial.latestDemand, "new-page"),
            )
        val commit = f.onArchive
        f.onArchive = { completion, publication ->
            if (f.publications.size == 1) {
                f.repair.stored = next
                AppResult.Success(false)
            } else commit(completion, publication)
        }
        assertEquals(AppResult.Success(1), f.retire()(f.access, f.conversation))
        assertEquals(2, f.publications.size)
        assertEquals(next, f.publications.last().second.expected)
        assertEquals(next.pass, f.repair.stored?.pass)
    }

    @Test
    fun eachOperationalStorageFailureStopsAtItsBoundary() = runTest {
        for (boundary in
            listOf(
                "journal",
                "completion",
                "message",
                "history",
                "progress",
                "archive",
                "remove",
            )) {
            val f = SpaceOriginalRetirementFixture()
            f.readFailure = boundary
            assertEquals(
                AppResult.Failed(f.base.storageFailure),
                f.retire()(f.access, f.conversation),
            )
            assertEquals(boundary, f.operations.last())
            assertTrue(f.publications.size <= 1)
            assertTrue(f.removals.size <= 1)
        }
    }

    @Test
    fun deniedOrReplacedAccountCannotAcquireTheReceiptLedger() = runTest {
        for (denied in listOf(false, true)) {
            val f = SpaceOriginalRetirementFixture()
            if (denied) f.base.deny() else f.base.identity.value = AppResult.Success(null)
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.retire()(f.access, f.conversation)).failure.kind,
            )
            assertTrue(f.operations.isEmpty())
        }
    }

    @Test
    fun denialOrReplacementDuringEveryIoCannotPublishSuccessToTheOldOwner() = runTest {
        for (boundary in
            listOf(
                "journal",
                "completion",
                "message",
                "history",
                "progress",
                "archive",
                "remove",
            )) for (denied in listOf(false, true)) {
            val f = SpaceOriginalRetirementFixture()
            f.afterIo = { point ->
                if (point == boundary) {
                    if (denied) f.base.deny() else f.base.identity.value = AppResult.Success(null)
                }
            }
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.retire()(f.access, f.conversation)).failure.kind,
            )
            assertEquals(boundary, f.operations.last())
        }
    }

    @Test
    fun cancellationWaitsForUncooperativeArchivalThenPreventsCleanupAndRetry() = runTest {
        val f = SpaceOriginalRetirementFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onArchive = { _, _ ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                AppResult.Success(false)
            }
        }
        val job = async { f.retire()(f.access, f.conversation) }
        entered.await()
        job.cancel()
        assertFalse(job.isCompleted)
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, f.publications.size)
        assertTrue(f.removals.isEmpty())
    }

    @Test
    fun malformedOrOversizedLedgerStopsBeforeAnyOriginalAcquisition() = runTest {
        for (kind in 0..2) {
            val f = SpaceOriginalRetirementFixture()
            when (kind) {
                0 -> f.journal += f.journal.single()
                1 -> for (number in 2..129) f.addOriginal(number)
                2 -> f.journal[0] = f.journal[0].copy(eventIds = listOf("invalid"))
            }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.retire()(f.access, f.conversation)).failure.kind,
            )
            assertEquals(listOf("journal"), f.operations)
            assertTrue(f.publications.isEmpty())
        }
    }

    @Test
    fun malformedRetainedCommandArchiveHistoryOrProgressCannotAuthorizeCleanup() = runTest {
        for (kind in 0..3) {
            val f = SpaceOriginalRetirementFixture()
            when (kind) {
                0 -> f.records.clear()
                1 ->
                    f.archives[f.first.intent.id] =
                        CollaborationSpaceMessageCompletion(f.first, emptyList())
                2 ->
                    f.originals[f.first.intent.id] =
                        f.originals.getValue(f.first.intent.id).copy(text = null)
                3 -> f.repair.stored = f.repair.initial.copy(generation = "invalid")
            }
            assertIs<AppResult.Failed>(f.retire()(f.access, f.conversation))
            assertTrue(f.publications.isEmpty())
            assertTrue(f.removals.isEmpty())
        }
    }

    @Test
    fun oldCredentialsAndNoSelectedCompanyStillPermitOwnedLocalRetirement() = runTest {
        val f = SpaceOriginalRetirementFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        assertEquals(AppResult.Success(1), f.retire()(f.access, f.conversation))
        assertEquals(0, f.base.native.pageCalls)
    }
}
