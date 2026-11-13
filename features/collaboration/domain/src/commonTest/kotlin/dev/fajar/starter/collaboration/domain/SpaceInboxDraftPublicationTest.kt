package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxDraftPublicationTest {
    private val empty = CollaborationSpaceInboxConversation(null, null, null, emptyList())
    private val generation = "d4000000-0000-4000-8000-000000000001"

    @Test
    fun firstDraftAllocatesOnePositionAndAReplayReusesTheSameSlot() {
        val f = SpaceComposerUseCaseFixture()
        val metadata = assertNotNull(f.base.canonical)
        val first = publication(metadata, empty, f.original)
        assertEquals(1L, first.lastOrdinal)
        assertEquals(f.original, first.draft?.value)
        val held =
            CollaborationSpaceInboxConversation(
                CollaborationSpaceInboxVersion(generation, 0, 1),
                first.entry,
                first.draft,
                emptyList(),
            )
        val replay = publication(metadata, held, f.original)
        assertEquals(1L, replay.lastOrdinal)
        assertEquals(first.entry, replay.entry)
        assertEquals(first.draft, replay.draft)
    }

    @Test
    fun clearingEditorRetainsItsRevisionAndRevealsTheExistingMessage() {
        val f = SpaceComposerUseCaseFixture()
        val metadata = assertNotNull(f.base.canonical)
        val candidate = activity(f, 1)
        val original =
            CollaborationSpaceInboxDraft(
                f.original,
                CollaborationSpaceInboxPosition(f.original.savedAt.toEpochMilliseconds(), 2),
            )
        val entry =
            collaborationSpaceInboxEntry(
                metadata.id,
                metadata.createdAt.toEpochMilliseconds(),
                original,
                candidate,
            )
        val held =
            CollaborationSpaceInboxConversation(
                CollaborationSpaceInboxVersion(generation, 1, 2),
                entry,
                original,
                listOf(candidate),
            )
        val cleared = publication(metadata, held, f.submission.nextDraft)
        assertNull(cleared.entry.draft)
        assertEquals(f.submission.nextDraft, cleared.draft?.value)
        assertEquals(candidate, cleared.entry.newest)
        assertEquals(candidate.position, cleared.entry.position)
        assertEquals(3L, cleared.lastOrdinal)
        assertTrue(cleared.activities.isEmpty())
    }

    @Test
    fun snapshotCaptureDetachesCandidateListsAndRejectsContradictoryRows() {
        val f = SpaceComposerUseCaseFixture()
        val metadata = assertNotNull(f.base.canonical)
        val candidate = activity(f, 1)
        val mutable = mutableListOf(candidate)
        val entry =
            collaborationSpaceInboxEntry(
                metadata.id,
                metadata.createdAt.toEpochMilliseconds(),
                null,
                candidate,
            )
        val held =
            CollaborationSpaceInboxConversation(
                CollaborationSpaceInboxVersion(generation, 0, 1),
                entry,
                null,
                mutable,
            )
        val captured =
            assertIs<AppResult.Success<CollaborationSpaceInboxConversation>>(
                    captureCollaborationSpaceInboxConversation(metadata.id, held)
                )
                .value
        mutable.clear()
        assertEquals(listOf(candidate), captured.candidates)
        assertIs<AppResult.Failed>(captureCollaborationSpaceInboxConversation(metadata.id, held))
        assertIs<AppResult.Failed>(
            captureCollaborationSpaceInboxConversation(
                metadata.id,
                captured.copy(candidates = listOf(candidate, candidate)),
            )
        )
        assertIs<AppResult.Failed>(
            captureCollaborationSpaceInboxConversation(metadata.id, captured.copy(version = null))
        )
    }

    @Test
    fun anotherCanonicalConversationOrChangedCreationFactCannotReplaceTheTarget() {
        val f = SpaceComposerUseCaseFixture()
        val metadata = assertNotNull(f.base.canonical)
        val first = publication(metadata, empty, f.original)
        val held =
            CollaborationSpaceInboxConversation(
                CollaborationSpaceInboxVersion(generation, 0, 1),
                first.entry,
                first.draft,
                emptyList(),
            )
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxDraftPublication(
                metadata.copy(id = generation),
                held,
                f.original,
            )
        )
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxDraftPublication(
                metadata,
                held.copy(entry = first.entry.copy(createdAt = 0)),
                f.original,
            )
        )
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxDraftPublication(
                metadata,
                held,
                f.original.copy(submissionId = f.original.revision),
            )
        )
    }

    @Test
    fun exhaustedInsertionCounterCannotWrapOrPublishANewEditor() {
        val f = SpaceComposerUseCaseFixture()
        val metadata = assertNotNull(f.base.canonical)
        val held =
            empty.copy(
                version =
                    CollaborationSpaceInboxVersion(
                        generation,
                        0,
                        COLLABORATION_SPACE_INBOX_MAX_ORDER,
                    )
            )
        val failure =
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxDraftPublication(metadata, held, f.original)
                )
                .failure
        assertEquals(FailureKind.Conflict, failure.kind)
        assertIs<AppResult.Failed>(
            captureCollaborationSpaceInboxConversation(
                metadata.id,
                held.copy(version = held.version!!.copy(lastOrdinal = -1)),
            )
        )
    }

    private fun activity(f: SpaceComposerUseCaseFixture, ordinal: Long) =
        CollaborationSpaceInboxActivity(
            CollaborationSpaceInboxSource.GroupOriginal(f.base.id),
            CollaborationSpaceInboxPosition(f.original.savedAt.toEpochMilliseconds(), ordinal),
            CollaborationSpaceInboxPreview(
                CollaborationSpaceInboxContent.TEXT,
                "Retained message",
                f.original.savedAt.toEpochMilliseconds(),
                CollaborationMessageDelivery.PENDING,
            ),
        )

    private fun publication(
        metadata: CollaborationSpaceConversationMetadata,
        current: CollaborationSpaceInboxConversation,
        replacement: CollaborationDraft,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceInboxPublication>>(
                collaborationSpaceInboxDraftPublication(metadata, current, replacement)
            )
            .value
}
