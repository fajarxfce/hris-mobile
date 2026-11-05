package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Instant

private val summaryTime = Instant.parse("2026-11-05T00:00:00Z")

private fun summaryCheckpoint(
    count: Int = 0,
    phase: CollaborationListSyncPhase = CollaborationListSyncPhase.CHANGES,
) =
    CollaborationListSyncCheckpoint(
        CollaborationListCheckpoint(syncId(9500), 0, count, summaryTime, 2),
        phase == CollaborationListSyncPhase.CHANGES,
        CollaborationListSyncState(
            syncId(9100),
            phase,
            if (phase == CollaborationListSyncPhase.BOOTSTRAP) null else "old",
        ),
    )

private fun deltaPage(
    vararg facts: Pair<Long, CollaborationSummaryFact>,
    pending: Boolean = false,
) =
    CollaborationSummaryChanges(
        facts.map { CollaborationSummaryChange(it.first, it.second) },
        "next",
        false,
        pending,
        summaryTime,
    )

class CollaborationListSyncPolicyTest {
    @Test
    fun bootstrapNeverPublishesAndCatchupWaitsForNoPendingWork() {
        val initial = summaryCheckpoint(phase = CollaborationListSyncPhase.BOOTSTRAP)
        val first =
            assertIs<AppResult.Success<CollaborationListSyncWrite>>(
                    planCollaborationSummaryBootstrap(
                        initial,
                        CollaborationSummaryBootstrap(
                            listOf(CollaborationSummaryFact(syncId(1), 0, syncSummary(1))),
                            null,
                            "changes",
                            false,
                            summaryTime,
                        ),
                        CollaborationListSyncWindow(initial, emptyList()),
                        summaryTime,
                    )
                )
                .value
        assertFalse(first.checkpoint.published)
        assertFalse(first.caughtUp)
        assertEquals(CollaborationListSyncPhase.CATCHUP, first.checkpoint.sync?.phase)
        val waiting =
            assertIs<AppResult.Success<CollaborationListSyncWrite>>(
                    planCollaborationSummaryChanges(
                        first.checkpoint,
                        deltaPage(pending = true),
                        CollaborationListSyncWindow(first.checkpoint, emptyList()),
                        summaryTime,
                    )
                )
                .value
        assertFalse(waiting.checkpoint.published)
        assertEquals(5, waiting.retryAfterSeconds)
        val complete =
            assertIs<AppResult.Success<CollaborationListSyncWrite>>(
                    planCollaborationSummaryChanges(
                        waiting.checkpoint,
                        deltaPage(),
                        CollaborationListSyncWindow(waiting.checkpoint, emptyList()),
                        summaryTime,
                    )
                )
                .value
        assertTrue(complete.caughtUp)
        assertTrue(complete.checkpoint.published)
        assertEquals(1, complete.checkpoint.local.count)
    }

    @Test
    fun olderRestoreCannotEraseNewerDeletionAndDuplicateReferencesFoldToHighestRevision() {
        val current = summaryCheckpoint(1)
        val window =
            CollaborationListSyncWindow(
                current,
                listOf(CollaborationListStoredFact(syncId(1), 7, null)),
            )
        val old =
            assertIs<AppResult.Success<CollaborationListSyncWrite>>(
                    planCollaborationSummaryChanges(
                        current,
                        deltaPage(1L to CollaborationSummaryFact(syncId(1), 6, syncSummary(1))),
                        window,
                        summaryTime,
                    )
                )
                .value
        assertTrue(old.facts.isEmpty())
        assertEquals(1, old.checkpoint.local.count)
        val restored =
            assertIs<AppResult.Success<CollaborationListSyncWrite>>(
                    planCollaborationSummaryChanges(
                        current,
                        deltaPage(
                            1L to CollaborationSummaryFact(syncId(1), 7, null),
                            2L to CollaborationSummaryFact(syncId(1), 8, syncSummary(1)),
                        ),
                        window,
                        summaryTime,
                    )
                )
                .value
        assertEquals(listOf(CollaborationSummaryFact(syncId(1), 8, syncSummary(1))), restored.facts)
        assertEquals(1, restored.checkpoint.local.count)
        assertIs<AppResult.Failed>(
            planCollaborationSummaryChanges(
                current,
                deltaPage(1L to CollaborationSummaryFact(syncId(1), 7, syncSummary(1))),
                window,
                summaryTime,
            )
        )
    }

    @Test
    fun serverRevisionZeroCanReplaceAnUnversionedInteractiveFallback() {
        val current = summaryCheckpoint(1)
        val renamed =
            syncSummary(1).copy(peer = syncSummary(1).peer.copy(displayName = "Updated name"))
        val result =
            assertIs<AppResult.Success<CollaborationListSyncWrite>>(
                    planCollaborationSummaryChanges(
                        current,
                        deltaPage(1L to CollaborationSummaryFact(syncId(1), 0, renamed)),
                        CollaborationListSyncWindow(
                            current,
                            listOf(CollaborationListStoredFact(syncId(1), null, syncSummary(1))),
                        ),
                        summaryTime,
                    )
                )
                .value
        assertEquals(listOf(CollaborationSummaryFact(syncId(1), 0, renamed)), result.facts)
        assertEquals(1, result.checkpoint.local.count)
    }

    @Test
    fun unchangedUpdatesAtCapacityDoNotGrowCountsButNewIdentitiesAndUnsafeVersionsFail() {
        val current = summaryCheckpoint(COLLABORATION_LIST_SYNC_CAPACITY)
        val empty =
            assertIs<AppResult.Success<CollaborationListSyncWrite>>(
                    planCollaborationSummaryChanges(
                        current,
                        deltaPage(),
                        CollaborationListSyncWindow(current, emptyList()),
                        summaryTime,
                    )
                )
                .value
        assertEquals(current.local.count, empty.checkpoint.local.count)
        assertTrue(empty.facts.isEmpty())
        assertIs<AppResult.Failed>(
            planCollaborationSummaryChanges(
                current,
                deltaPage(1L to CollaborationSummaryFact(syncId(1), 0, syncSummary(1))),
                CollaborationListSyncWindow(current, emptyList()),
                summaryTime,
            )
        )
        val fresh = summaryCheckpoint()
        assertIs<AppResult.Failed>(
            planCollaborationSummaryChanges(
                fresh,
                deltaPage(
                    1L to
                        CollaborationSummaryFact(
                            syncId(1),
                            COLLABORATION_SUMMARY_REVISION_LIMIT + 1,
                            syncSummary(1),
                        )
                ),
                CollaborationListSyncWindow(fresh, emptyList()),
                summaryTime,
            )
        )
    }

    @Test
    fun contradictoryEqualRevisionAndChangedCanonicalIdentityCannotEnterTheCache() {
        val current = summaryCheckpoint(1)
        val original = syncSummary(1)
        val renamed = original.copy(peer = original.peer.copy(displayName = "Contradictory"))
        val window =
            CollaborationListSyncWindow(
                current,
                listOf(CollaborationListStoredFact(syncId(1), 3, original)),
            )
        assertIs<AppResult.Failed>(
            planCollaborationSummaryChanges(
                current,
                deltaPage(1L to CollaborationSummaryFact(syncId(1), 3, renamed)),
                window,
                summaryTime,
            )
        )
        assertIs<AppResult.Failed>(
            planCollaborationSummaryChanges(
                current,
                deltaPage(
                    1L to CollaborationSummaryFact(syncId(1), 4, original),
                    2L to CollaborationSummaryFact(syncId(1), 4, renamed),
                ),
                window,
                summaryTime,
            )
        )
        assertIs<AppResult.Failed>(
            planCollaborationSummaryChanges(
                current,
                deltaPage(
                    1L to
                        CollaborationSummaryFact(
                            syncId(1),
                            4,
                            original.copy(conversation = original.conversation.copy(createdAt = 1)),
                        )
                ),
                window,
                summaryTime,
            )
        )
    }

    @Test
    fun pageOrderingCheckpointIdentityAndFiniteBootstrapContinuationAreRequired() {
        val initial = summaryCheckpoint(phase = CollaborationListSyncPhase.BOOTSTRAP)
        val first =
            assertIs<AppResult.Success<CollaborationListSyncWrite>>(
                    planCollaborationSummaryBootstrap(
                        initial,
                        CollaborationSummaryBootstrap(
                            listOf(CollaborationSummaryFact(syncId(1), 0, syncSummary(1))),
                            "next",
                            null,
                            false,
                            summaryTime,
                        ),
                        CollaborationListSyncWindow(initial, emptyList()),
                        summaryTime,
                        1,
                    )
                )
                .value
                .checkpoint
        assertIs<AppResult.Failed>(
            planCollaborationSummaryBootstrap(
                first,
                CollaborationSummaryBootstrap(
                    listOf(CollaborationSummaryFact(syncId(1), 0, syncSummary(1))),
                    null,
                    "done",
                    false,
                    summaryTime,
                ),
                CollaborationListSyncWindow(first, emptyList()),
                summaryTime,
                1,
            )
        )
        val current = summaryCheckpoint().let { it.copy(sync = it.sync!!.copy(lastPosition = 5)) }
        assertIs<AppResult.Failed>(
            planCollaborationSummaryChanges(
                current,
                deltaPage(5L to CollaborationSummaryFact(syncId(1), 0, null)),
                CollaborationListSyncWindow(current, emptyList()),
                summaryTime,
            )
        )
        assertIs<AppResult.Failed>(
            planCollaborationSummaryChanges(
                current,
                deltaPage(),
                CollaborationListSyncWindow(initial, emptyList()),
                summaryTime,
            )
        )
        val last = first.copy(sync = first.sync!!.copy(bootstrapPages = 999))
        assertIs<AppResult.Failed>(
            planCollaborationSummaryBootstrap(
                last,
                CollaborationSummaryBootstrap(
                    listOf(CollaborationSummaryFact(syncId(2), 0, syncSummary(2))),
                    "over-limit",
                    null,
                    false,
                    summaryTime,
                ),
                CollaborationListSyncWindow(last, emptyList()),
                summaryTime,
                1,
            )
        )
    }

    @Test
    fun onlyDefinedCursorErrorsRequestReplacement() {
        for (code in
            listOf(
                "invalid_collaboration_sync_cursor",
                "collaboration_summary_scope_changed",
                "collaboration_summary_cursor_out_of_range",
                "collaboration_summary_cursor_expired",
                "collaboration_summary_bootstrap_limit",
            )) {
            assertTrue(
                collaborationSummaryCursorNeedsReset(
                    Failure(FailureKind.Conflict, "Fixture", code = code)
                )
            )
        }
        for (code in
            listOf(
                "permission_denied",
                "mfa_required",
                "client.collaboration_invalid_summary_page",
                "fixture.network",
            )) {
            assertFalse(
                collaborationSummaryCursorNeedsReset(
                    Failure(FailureKind.AccessDenied, "Fixture", code = code)
                )
            )
        }
    }
}
