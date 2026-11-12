package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CompleteCollaborationSpaceHistoryRecoveryTurnTest {
    @Test
    fun originalCompletedPassPreservesPrimaryWork() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        val before = assertNotNull(f.snapshot).demand
        assertEquals(AppResult.Success(true), f.run())
        assertTrue(f.retained().complete)
        assertFalse(f.retained().unresolved)
        assertEquals(before.copy(historyRecovery = f.retained()), assertNotNull(f.snapshot).demand)
        assertEquals(1, f.writes)
        assertEquals(listOf<Pair<String?, Int>>(null to 1, null to 1), f.requests)
    }

    @Test
    fun publishedAndOriginalAliasKeepTheirLogicalHistoricalDevice() = runTest {
        for (alias in listOf(false, true)) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            f.published(alias)
            assertNotEquals(
                f.runtime.deviceId,
                (f.attempt as CollaborationSpaceHistoryRecoveryAttempt.Published)
                    .history
                    .traversal
                    .deviceId,
            )
            assertEquals(AppResult.Success(true), f.run())
            assertTrue(f.retained().complete)
            assertEquals(2, f.reads["progress"])
        }
    }

    @Test
    fun unresolvedEofCompletesAndSleepsWithoutAnotherRound() = runTest {
        for (space in listOf(false, true)) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            if (space) f.published()
            f.pending(unresolved = 2)
            assertEquals(AppResult.Success(true), f.run())
            assertTrue(f.retained().complete)
            assertTrue(f.retained().unresolved)
            assertFalse(f.retained().moreHistoryPages)
            assertNull(f.retained().retry)
        }
    }

    @Test
    fun olderLocalPassCannotFinishRequestedGlobalPass() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.pending(older = true)
        assertEquals(AppResult.Success(true), f.run())
        assertFalse(f.retained().complete)
        assertNull(f.retained().afterPartition)
        assertEquals(f.generation, f.retained().generation)
    }

    @Test
    fun currentEofReceiptSurvivesLocalPromotion() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.pending(unresolved = 1)
        f.progress =
            assertNotNull(f.progress)
                .copy(recovery = CollaborationHistoryRecoveryPosition(f.base.id(964), 2))
        assertEquals(AppResult.Success(true), f.run())
        assertTrue(f.retained().complete)
        assertTrue(f.retained().unresolved)
    }

    @Test
    fun missingCompletionReceiptCannotAdvancePendingPage() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.pending()
        f.attempt =
            (f.attempt as CollaborationSpaceHistoryRecoveryAttempt.Original).copy(completion = null)
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun absentProgressNeedsNoRecoveryPass() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.progress = null
        f.attempt =
            (f.attempt as CollaborationSpaceHistoryRecoveryAttempt.Original).copy(
                read = CollaborationHistoryRecoveryRead.Absent
            )
        assertEquals(AppResult.Success(true), f.run())
        assertTrue(f.retained().complete)
    }

    @Test
    fun absentReadCannotHideProgressPublishedBeforeCompletion() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.attempt =
            (f.attempt as CollaborationSpaceHistoryRecoveryAttempt.Original).copy(
                read = CollaborationHistoryRecoveryRead.Absent
            )
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun emptyInternalRowAdvancesWithoutInventingHistory() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.page = CollaborationAccountIncomingHistoryLocatorPage(emptyList(), "partition-next")
        f.turn = f.turn.copy(page = f.page)
        f.attempt = null
        assertEquals(AppResult.Success(true), f.run())
        assertEquals("partition-next", f.retained().afterPartition)
        assertFalse(f.retained().complete)
        assertNull(f.reads["progress"])
    }

    @Test
    fun emptyAccountEofCompletesWithoutContent() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.page = CollaborationAccountIncomingHistoryLocatorPage(emptyList(), null)
        f.turn = f.turn.copy(page = f.page)
        f.attempt = null
        assertEquals(AppResult.Success(true), f.run())
        assertTrue(f.retained().complete)
        assertFalse(f.retained().unresolved)
    }

    @Test
    fun sourceServingAnotherCompanyOrSpaceRetainsUnresolvedEvidence() = runTest {
        for (space in listOf(false, true)) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            val row =
                if (space)
                    CollaborationAccountIncomingHistoryLocator.Space(
                        f.base.id(969),
                        f.company.history,
                    )
                else f.company.copy(companyId = f.base.id(969))
            f.page = f.page.copy(locators = listOf(row))
            f.turn = f.turn.copy(page = f.page)
            f.attempt = CollaborationSpaceHistoryRecoveryAttempt.Unavailable
            assertEquals(AppResult.Success(true), f.run())
            assertTrue(f.retained().complete)
            assertTrue(f.retained().unresolved)
            assertNull(f.reads["progress"])
            assertFalse(f.calls.contains("metadata"))
        }
    }

    @Test
    fun missingMetadataMustBeIndependentlyConfirmed() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.base.retained = null
        f.attempt = CollaborationSpaceHistoryRecoveryAttempt.Unavailable
        assertEquals(AppResult.Success(true), f.run())
        assertTrue(f.retained().unresolved)
    }

    @Test
    fun callerCannotLabelAvailableHistoryAsUnavailable() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.attempt = CollaborationSpaceHistoryRecoveryAttempt.Unavailable
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun changedSourceOrRegistrationCannotConfirmUnavailable() = runTest {
        for (stage in listOf("source", "registration")) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            f.base.retained = null
            f.attempt = CollaborationSpaceHistoryRecoveryAttempt.Unavailable
            f.hooks["locators1"] = {
                if (stage == "source") f.base.installed = null else f.base.registration = null
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun changedLocatorCoverageDoesNotAdvanceSelectedCursor() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.hooks["progress1"] = { f.page = f.page.copy(next = "inserted-partition") }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun actualHistoricalCoordinatesMustMatchSelectedLocator() = runTest {
        for (field in listOf("device", "conversation", "company")) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            val a = f.attempt as CollaborationSpaceHistoryRecoveryAttempt.Original
            val h = a.history
            val changed =
                when (field) {
                    "device" -> h.copy(traversal = h.traversal.copy(deviceId = f.base.id(999)))
                    "conversation" ->
                        h.copy(traversal = h.traversal.copy(conversationId = f.base.id(999)))
                    else ->
                        h.copy(
                            conversation =
                                h.conversation.copy(
                                    conversation =
                                        h.conversation.conversation.copy(
                                            position =
                                                f.base.position.copy(companyId = f.base.id(999))
                                        )
                                )
                        )
                }
            f.attempt = a.copy(history = changed)
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun companyAndSpaceCheckpointProvidersStayDistinct() = runTest {
        for (space in listOf(false, true)) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            if (space) f.published()
            f.progress =
                assertNotNull(f.progress)
                    .copy(
                        provider =
                            if (space) null
                            else
                                CollaborationHistoryProviderIdentity(f.base.id(990), f.base.id(991))
                    )
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun metadataReplacementAfterReadRejectsCompletion() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.hooks["progress1"] = {
            val value = assertNotNull(f.base.retained)
            f.base.retained =
                value.copy(room = value.room.copy(roomId = "!replaced:matrix.hris.internal"))
        }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun lateRootAccountSourceRegistrationAndDenialStopTheWrite() = runTest {
        for (kind in listOf("root", "account", "source", "registration", "denial")) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            f.hooks["progress1"] = {
                when (kind) {
                    "root" -> f.root = null
                    "account" -> f.base.native.setup.session = null
                    "source" -> f.base.installed = null
                    "registration" -> f.base.registration = null
                    else -> f.base.directory.deny()
                }
            }
            val failed = assertIs<AppResult.Failed>(f.run())
            if (kind == "denial") assertEquals(f.base.directory.denied, failed.failure)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun operationalReadFailureIsNotUnavailableHistory() = runTest {
        for (stage in listOf("locators", "progress", "root")) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            f.failures[stage] = f.base.directory.storageFailure
            assertEquals(AppResult.Failed(f.base.directory.storageFailure), f.run())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun laterGlobalRequestPromotesOnlyAfterCompletedCurrentPass() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        val later = f.base.id(965)
        f.hooks["demand1"] = { f.history(f.recovery.copy(requestedGeneration = later)) }
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(CollaborationSpaceHistoryRecovery(later), f.retained())
    }

    @Test
    fun concurrentPrimaryCasLossDoesNotOverwriteOrRetry() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.hooks["write1"] = {
            val snapshot = assertNotNull(f.snapshot)
            f.snapshot =
                snapshot.copy(
                    generation = f.base.id(995),
                    demand = snapshot.demand.copy(rescanGeneration = f.base.id(996)),
                )
        }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(f.base.id(996), assertNotNull(f.snapshot).demand.rescanGeneration)
        assertEquals(f.recovery, f.retained())
        assertEquals(1, f.writes)
    }

    @Test
    fun lostStorageReplyRequiresExactRetainedAdvancement() = runTest {
        for (commit in listOf(false, true)) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            f.lostReply = true
            f.commit = commit
            val result = f.run()
            if (commit) assertEquals(AppResult.Success(true), result)
            else assertEquals(AppResult.Failed(f.base.directory.storageFailure), result)
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun fabricatedOrMalformedWriteRepliesAreNotReceipts() = runTest {
        for (kind in listOf("fabricated", "unchanged", "different")) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            when (kind) {
                "fabricated" -> {
                    f.commit = false
                    f.fabricatedReply = true
                }
                "unchanged" -> f.response = { it.copy(generation = f.base.id(4)) }
                else -> f.response = {
                        it.copy(demand = it.demand.copy(historyRecovery = f.recovery))
                    }
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun laterIndependentPrimaryWorkSurvivesReadback() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.hooks["written1"] = {
            val value = assertNotNull(f.snapshot)
            f.snapshot =
                value.copy(
                    generation = f.base.id(998),
                    demand = value.demand.copy(rescanGeneration = f.base.id(997)),
                )
        }
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(f.base.id(997), assertNotNull(f.snapshot).demand.rescanGeneration)
    }

    @Test
    fun laterRequestAtActiveCursorIsPreservedDuringConfirmation() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.page = f.page.copy(next = "next-partition")
        f.turn = f.turn.copy(page = f.page)
        f.hooks["written1"] = { f.history(f.retained().copy(requestedGeneration = f.base.id(998))) }
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(f.base.id(998), f.retained().requestedGeneration)
        assertEquals("next-partition", f.retained().afterPartition)
    }

    @Test
    fun replayedTurnDoesNotWriteAgain() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        assertEquals(AppResult.Success(true), f.run())
        assertIs<AppResult.Failed>(f.run())
        assertEquals(1, f.writes)
    }

    @Test
    fun pendingInputCopiesMutableReferenceListBeforeSuspension() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.pending()
        val attempt = f.attempt as CollaborationSpaceHistoryRecoveryAttempt.Original
        val read = attempt.read as CollaborationHistoryRecoveryRead.Pending
        val events = read.references.events.toMutableList()
        f.attempt =
            attempt.copy(read = read.copy(references = read.references.copy(events = events)))
        f.hooks["locators1"] = { events.clear() }
        assertEquals(AppResult.Success(true), f.run())
        assertTrue(events.isEmpty())
    }

    @Test
    fun selectedMetadataListIsCapturedBeforeSuspension() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        val rows = f.page.locators.toMutableList()
        f.turn = f.turn.copy(page = f.page.copy(locators = rows))
        f.hooks["registration1"] = { rows.clear() }
        assertEquals(AppResult.Success(true), f.run())
    }

    @Test
    fun oversizedInputIsRejectedBeforeRepositoryReads() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.pending()
        val attempt = f.attempt as CollaborationSpaceHistoryRecoveryAttempt.Original
        val read = attempt.read as CollaborationHistoryRecoveryRead.Pending
        f.attempt =
            attempt.copy(
                read =
                    read.copy(
                        references =
                            read.references.copy(
                                events = List(17) { read.references.events.first() }
                            )
                    )
            )
        assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.writes)
    }

    @Test
    fun timeoutRetainsGlobalTurnAndPrimaryWork() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        val before = f.snapshot
        f.hooks["progress1"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(before, f.snapshot)
        assertEquals(0, f.writes)
    }

    @Test
    fun cancellationPropagatesWithoutCompletingTheTurn() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        val reached = CompletableDeferred<Unit>()
        f.hooks["progress1"] = {
            reached.complete(Unit)
            awaitCancellation()
        }
        val task = async { f.run() }
        reached.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(f.recovery, f.retained())
        assertEquals(0, f.writes)
    }

    @Test
    fun absenceIsRecheckedAfterFinalCoverageRead() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        val appeared = f.progress
        f.progress = null
        f.attempt =
            (f.attempt as CollaborationSpaceHistoryRecoveryAttempt.Original).copy(
                read = CollaborationHistoryRecoveryRead.Absent
            )
        f.hooks["locators2"] = { f.progress = appeared }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
    }
}
