package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days

class SpaceInboxComposerPublicationTest {
    @Test
    fun theFirstGestureHasAPendingPreviewAndAnExplicitEmptyEditor() {
        val f = SpaceComposerUseCaseFixture()
        val value = initial(f)
        assertEquals(f.submission.nextDraft, value.draft?.value)
        assertNull(value.entry.draft)
        assertEquals(f.original.text, value.entry.newest?.preview?.text)
        assertEquals(CollaborationMessageDelivery.PENDING, value.entry.newest?.preview?.delivery)
        assertEquals(
            CollaborationSpaceInboxSource.GroupOriginal(f.submission.id),
            value.entry.newest?.source,
        )
        assertEquals(2L, value.lastOrdinal)
    }

    @Test
    fun anotherSendAtTheSameTimeGetsALaterDurablePosition() {
        val f = SpaceComposerUseCaseFixture()
        val first = initial(f)
        val current = snapshot(first)
        val next =
            f.submission.copy(
                original = f.original.copy(revision = nextId, text = "Second"),
                nextRevision = editorId,
            )
        val value =
            success(
                collaborationSpaceInboxComposerPublication(
                    f.access,
                    f.base.canonical!!,
                    current,
                    CollaborationSpaceInboxActivities(current.version, emptyList()),
                    next,
                    next.nextDraft,
                    false,
                )
            )
        assertEquals(4L, value.lastOrdinal)
        assertEquals(
            nextId,
            (value.entry.newest!!.source as CollaborationSpaceInboxSource.GroupOriginal).id,
        )
        assertTrue(
            compareCollaborationSpaceInboxPositions(value.entry.position, first.entry.position) > 0
        )
        assertEquals(next.submittedAt.toEpochMilliseconds(), value.entry.newest.preview?.timestamp)
    }

    @Test
    fun aBackwardsClockPreservesConversationOrderAndTheActualMessageTime() {
        val f = SpaceComposerUseCaseFixture()
        val first = initial(f)
        val current = snapshot(first)
        val next =
            f.submission.copy(
                original = f.original.copy(revision = nextId),
                nextRevision = editorId,
                submittedAt = f.submission.submittedAt - 1.days,
            )
        val value =
            success(
                collaborationSpaceInboxComposerPublication(
                    f.access,
                    f.base.canonical!!,
                    current,
                    CollaborationSpaceInboxActivities(current.version, emptyList()),
                    next,
                    next.nextDraft,
                    false,
                )
            )
        assertTrue(
            compareCollaborationSpaceInboxPositions(value.entry.position, first.entry.position) > 0
        )
        assertEquals(next.submittedAt.toEpochMilliseconds(), value.entry.newest?.preview?.timestamp)
        assertEquals(first.entry.position.timestamp, value.entry.position.timestamp)
    }

    @Test
    fun replayRetainsANewerEditorAndActualSentEvidenceWithoutAllocatingAgain() {
        val f = SpaceComposerUseCaseFixture()
        val first = initial(f)
        val sent =
            first.activities.single().let {
                it.copy(preview = it.preview!!.copy(delivery = CollaborationMessageDelivery.SENT))
            }
        val newer =
            CollaborationSpaceInboxDraft(
                f.submission.nextDraft.copy(revision = editorId, text = "Keep typing"),
                CollaborationSpaceInboxPosition(f.submission.submittedAt.toEpochMilliseconds(), 3),
            )
        val current =
            snapshot(first)
                .copy(
                    version = version(3),
                    draft = newer,
                    candidates = listOf(sent),
                    entry =
                        collaborationSpaceInboxEntry(
                            f.original.conversationId,
                            first.entry.createdAt,
                            newer,
                            sent,
                        ),
                )
        val value =
            success(
                collaborationSpaceInboxComposerPublication(
                    f.access,
                    f.base.canonical!!,
                    current,
                    CollaborationSpaceInboxActivities(current.version, listOf(sent)),
                    f.submission,
                    newer.value,
                    true,
                )
            )
        assertEquals(newer, value.entry.draft)
        assertEquals(listOf(sent), value.activities)
        assertEquals(3L, value.lastOrdinal)
    }

    @Test
    fun aHiddenRetainedOriginalCannotBeRevivedBySubmissionReplay() {
        val f = SpaceComposerUseCaseFixture()
        val first = initial(f)
        val hidden = first.activities.single().copy(preview = null)
        val current =
            snapshot(first)
                .copy(
                    candidates = emptyList(),
                    entry =
                        collaborationSpaceInboxEntry(
                            f.original.conversationId,
                            first.entry.createdAt,
                            first.draft,
                            null,
                        ),
                )
        val value =
            success(
                collaborationSpaceInboxComposerPublication(
                    f.access,
                    f.base.canonical!!,
                    current,
                    CollaborationSpaceInboxActivities(current.version, listOf(hidden)),
                    f.submission,
                    f.submission.nextDraft,
                    true,
                )
            )
        assertNull(value.entry.newest)
        assertEquals(listOf(hidden), value.activities)
        assertEquals(first.lastOrdinal, value.lastOrdinal)
    }

    @Test
    fun mismatchedIndexVersionsAndUnprovenSourceAliasesAreRejected() {
        val f = SpaceComposerUseCaseFixture()
        val first = initial(f)
        val current = snapshot(first)
        val moved =
            CollaborationSpaceInboxActivities(
                current.version!!.copy(revision = 1),
                first.activities,
            )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxComposerPublication(
                        f.access,
                        f.base.canonical!!,
                        current,
                        moved,
                        f.submission,
                        f.submission.nextDraft,
                        true,
                    )
                )
                .failure
                .kind,
        )
        val foreign =
            first.activities
                .single()
                .copy(
                    source =
                        CollaborationSpaceInboxSource.CompanyOriginal(
                            f.access.spaceId,
                            f.original.conversationId,
                            f.submission.id,
                        )
                )
        assertIs<AppResult.Failed>(
            captureCollaborationSpaceInboxComposerAnchor(
                f.submission.id,
                CollaborationSpaceInboxActivities(current.version, listOf(foreign)),
            )
        )
        assertIs<AppResult.Failed>(
            captureCollaborationSpaceInboxComposerAnchor(
                f.submission.id,
                CollaborationSpaceInboxActivities(null, first.activities),
            )
        )
    }

    @Test
    fun counterExhaustionCannotWrapOrReplaceAnExistingAnchor() {
        val f = SpaceComposerUseCaseFixture()
        val current = f.inboxState.copy(version = version(COLLABORATION_SPACE_INBOX_MAX_ORDER - 1))
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxComposerPublication(
                        f.access,
                        f.base.canonical!!,
                        current,
                        CollaborationSpaceInboxActivities(current.version, emptyList()),
                        f.submission,
                        f.submission.nextDraft,
                        false,
                    )
                )
                .failure
                .kind,
        )
        val first = initial(f)
        val existing = snapshot(first)
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxComposerPublication(
                f.access,
                f.base.canonical!!,
                existing,
                CollaborationSpaceInboxActivities(existing.version, first.activities),
                f.submission,
                f.submission.nextDraft,
                false,
            )
        )
    }

    private fun initial(f: SpaceComposerUseCaseFixture) =
        success(
            collaborationSpaceInboxComposerPublication(
                f.access,
                f.base.canonical!!,
                f.inboxState,
                CollaborationSpaceInboxActivities(null, emptyList()),
                f.submission,
                f.submission.nextDraft,
                false,
            )
        )

    private fun snapshot(p: CollaborationSpaceInboxPublication) =
        CollaborationSpaceInboxConversation(
            version(p.lastOrdinal),
            p.entry,
            p.draft,
            p.activities.filter { it.preview != null },
        )

    private fun version(ordinal: Long) =
        CollaborationSpaceInboxVersion("d4000000-0000-4000-8000-000000000001", 0, ordinal)

    private fun <T> success(value: AppResult<T>) = assertIs<AppResult.Success<T>>(value).value

    private val nextId = "d4000000-0000-4000-8000-000000000002"
    private val editorId = "d4000000-0000-4000-8000-000000000003"
}
