package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxHistoryMutationPolicyTest {
    @Test
    fun bootstrapAndEmptyPageCaptureUseDestinationGuards() {
        val f = HistoryGlobalPolicyFixture()
        val first =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryMutation>>(
                    collaborationSpaceInboxHistoryMutation(
                        f.access,
                        null,
                        CollaborationSpaceInboxHistoryChange.Observe(f.trigger, f.pass, null),
                    )
                )
                .value
        assertEquals(CollaborationSpaceInboxHistoryGuard.Destination(null), first.guard)
        assertNull(first.expected)
        val expected =
            CollaborationSpaceInboxHistoryCheckpoint(
                CollaborationSpaceInboxHistoryProgressVersion(f.other, 0),
                first.replacement,
            )
        val captured =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryMutation>>(
                    collaborationSpaceInboxHistoryMutation(
                        f.access,
                        expected,
                        CollaborationSpaceInboxHistoryChange.CaptureCatalog(
                            CollaborationSpaceConversationCatalogRead.Page(
                                null,
                                emptyList(),
                                null,
                                false,
                            ),
                            null,
                        ),
                    )
                )
                .value
        assertEquals(CollaborationSpaceInboxHistoryGuard.Destination(null), captured.guard)
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Sources(null, null, false, false),
            captured.replacement.pass?.traversal,
        )
    }

    @Test
    fun successfulSourceAcknowledgementsGuardTheExactSourceButDeferralsDoNotClaimOne() {
        val f = HistoryGlobalPolicyFixture()
        val expected =
            CollaborationSpaceInboxHistoryCheckpoint(
                CollaborationSpaceInboxHistoryProgressVersion(f.other, 0),
                f.batch(f.start(), listOf(f.id)),
            )
        val retained =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryMutation>>(
                    collaborationSpaceInboxHistoryMutation(
                        f.access,
                        expected,
                        CollaborationSpaceInboxHistoryChange.AcknowledgeSource(
                            f.id,
                            f.source,
                            f.source,
                            null,
                        ),
                    )
                )
                .value
        assertEquals(CollaborationSpaceInboxHistoryGuard.Source(f.source), retained.guard)
        val deferred =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryMutation>>(
                    collaborationSpaceInboxHistoryMutation(
                        f.access,
                        expected,
                        CollaborationSpaceInboxHistoryChange.AcknowledgeSource(
                            f.id,
                            f.source,
                            null,
                            null,
                        ),
                    )
                )
                .value
        assertEquals(CollaborationSpaceInboxHistoryGuard.Destination(null), deferred.guard)
    }

    @Test
    fun finalCompletionRequiresTheCurrentDestinationEvenAfterAnEmptySourcePage() {
        val f = HistoryGlobalPolicyFixture()
        val expected =
            CollaborationSpaceInboxHistoryCheckpoint(
                CollaborationSpaceInboxHistoryProgressVersion(f.other, 0),
                f.sources(),
            )
        val page =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryMutation>>(
                    collaborationSpaceInboxHistoryMutation(
                        f.access,
                        expected,
                        CollaborationSpaceInboxHistoryChange.CaptureSources(
                            CollaborationSpaceInboxHistorySourcePage(emptyList(), null),
                            null,
                        ),
                    )
                )
                .value
        assertNull(page.replacement.completed)
        val ready = expected.copy(progress = page.replacement)
        val completed =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryMutation>>(
                    collaborationSpaceInboxHistoryMutation(
                        f.access,
                        ready,
                        CollaborationSpaceInboxHistoryChange.Complete(null),
                    )
                )
                .value
        assertEquals(CollaborationSpaceInboxHistoryGuard.Destination(null), completed.guard)
        assertEquals(f.pass, completed.replacement.completed?.passId)
    }

    @Test
    fun changedDestinationAndWrongPhaseCannotAdvanceAnUnrelatedCursor() {
        val f = HistoryGlobalPolicyFixture()
        val expected =
            CollaborationSpaceInboxHistoryCheckpoint(
                CollaborationSpaceInboxHistoryProgressVersion(f.other, 0),
                f.start(),
            )
        val foreign = CollaborationSpaceInboxVersion(f.other, 1, 1)
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxHistoryMutation(
                        f.access,
                        expected,
                        CollaborationSpaceInboxHistoryChange.Observe(f.trigger, f.other, foreign),
                    )
                )
                .failure
                .kind,
        )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxHistoryMutation(
                        f.access,
                        expected,
                        CollaborationSpaceInboxHistoryChange.Resume(foreign),
                    )
                )
                .failure
                .kind,
        )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxHistoryMutation(
                        f.access,
                        expected,
                        CollaborationSpaceInboxHistoryChange.CaptureSources(
                            CollaborationSpaceInboxHistorySourcePage(emptyList(), null),
                            null,
                        ),
                    )
                )
                .failure
                .kind,
        )
    }

    @Test
    fun aCallerCannotMutateRetainedExpectedOrReplacementQueuesAfterProposal() {
        val f = HistoryGlobalPolicyFixture()
        val ids = mutableListOf(f.id)
        val initial = f.start()
        val progress =
            initial.copy(
                pass =
                    initial.pass!!.copy(
                        traversal =
                            CollaborationSpaceInboxHistoryTraversal.Catalog(
                                null,
                                CollaborationSpaceInboxHistoryBatch(ids, null),
                            )
                    )
            )
        val expected =
            CollaborationSpaceInboxHistoryCheckpoint(
                CollaborationSpaceInboxHistoryProgressVersion(f.other, 0),
                progress,
            )
        val proposal =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryMutation>>(
                    collaborationSpaceInboxHistoryMutation(
                        f.access,
                        expected,
                        CollaborationSpaceInboxHistoryChange.Resume(null),
                    )
                )
                .value
        ids.clear()
        for (value in listOf(proposal.expected!!.progress, proposal.replacement)) assertEquals(
            listOf(f.id),
            assertIs<CollaborationSpaceInboxHistoryTraversal.Catalog>(value.pass?.traversal)
                .batch
                ?.remaining,
        )
    }

    @Test
    fun exhaustedPhysicalRevisionAllowsOnlyAnUnchangedCheckedOperation() {
        val f = HistoryGlobalPolicyFixture()
        val expected =
            CollaborationSpaceInboxHistoryCheckpoint(
                CollaborationSpaceInboxHistoryProgressVersion(
                    f.other,
                    COLLABORATION_SPACE_INBOX_MAX_ORDER,
                ),
                f.start(),
            )
        val noOp =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryMutation>>(
                    collaborationSpaceInboxHistoryMutation(
                        f.access,
                        expected,
                        CollaborationSpaceInboxHistoryChange.Resume(null),
                    )
                )
                .value
        assertEquals(expected.progress, noOp.replacement)
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxHistoryMutation(
                        f.access,
                        expected,
                        CollaborationSpaceInboxHistoryChange.Observe(
                            f.trigger.copy(historySignal = f.other),
                            f.other,
                            null,
                        ),
                    )
                )
                .failure
                .kind,
        )
    }
}
