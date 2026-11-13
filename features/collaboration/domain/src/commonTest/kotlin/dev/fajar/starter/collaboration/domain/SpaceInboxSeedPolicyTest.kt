package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Instant

class SpaceInboxSeedPolicyTest {
    @Test
    fun aQueuedOriginalSeedsItsOwnTimeTextAndReferenceWithoutCreatingAnEditor() {
        val f = SpaceInboxCommandFixture()
        val proposal =
            seed(f, f.queued(), empty(), CollaborationSpaceInboxActivities(null, emptyList()))
        assertNull(proposal.draft)
        assertNull(proposal.entry.draft)
        assertEquals(1L, proposal.lastOrdinal)
        assertEquals(f.queued().intent.text, proposal.entry.newest?.preview?.text)
        assertEquals(
            f.queued().intent.createdAt.toEpochMilliseconds(),
            proposal.entry.newest?.preview?.timestamp,
        )
        assertEquals(CollaborationMessageDelivery.PENDING, proposal.entry.newest?.preview?.delivery)
        assertEquals(CollaborationSpaceInboxRepairChange.Retain(f.queued().intent), proposal.repair)
    }

    @Test
    fun terminalOriginalsUseConfirmedOrHiddenPreviewsWithoutInferringFromAbsence() {
        val f = SpaceInboxCommandFixture()
        val sent = seed(f, f.sent(), empty(), CollaborationSpaceInboxActivities(null, emptyList()))
        assertEquals(CollaborationMessageDelivery.SENT, sent.entry.newest?.preview?.delivery)
        val cancelled =
            seed(
                f,
                f.queued().copy(state = CollaborationMessageState.Cancelled),
                empty(),
                CollaborationSpaceInboxActivities(null, emptyList()),
            )
        assertNull(cancelled.entry.newest)
        assertNull(cancelled.activities.single().preview)
        assertNotNull(cancelled.repair)
    }

    @Test
    fun anExistingEditedOrHiddenAnchorKeepsItsContentReceiptAndPosition() {
        for (hidden in listOf(false, true)) {
            val f = SpaceInboxCommandFixture()
            val old =
                f.anchor().let {
                    it.copy(
                        preview =
                            if (hidden) null
                            else
                                it.preview!!.copy(
                                    text = "Edited locally",
                                    delivery = CollaborationMessageDelivery.SENT,
                                )
                    )
                }
            val current =
                f.source.inboxState.let {
                    it.copy(
                        entry =
                            collaborationSpaceInboxEntry(
                                it.entry!!.conversationId,
                                it.entry.createdAt,
                                it.draft,
                                old.takeIf { it.preview != null },
                            ),
                        candidates = listOfNotNull(old.takeIf { it.preview != null }),
                    )
                }
            val proposal =
                seed(
                    f,
                    f.queued(),
                    current,
                    CollaborationSpaceInboxActivities(current.version, listOf(old)),
                )
            assertEquals(old, proposal.activities.single())
            assertEquals(current.version!!.lastOrdinal, proposal.lastOrdinal)
            assertNull(proposal.draft)
        }
    }

    @Test
    fun independentDraftContentAndOrdinalRemainUnchanged() {
        val f = SpaceInboxCommandFixture()
        val current = f.source.inboxState
        val draft =
            current.draft!!.copy(
                value =
                    current.draft.value.copy(
                        text = "Unsaved conversation thought",
                        submissionId = null,
                    )
            )
        val indexed =
            current.copy(
                draft = draft,
                entry =
                    collaborationSpaceInboxEntry(
                        current.entry!!.conversationId,
                        current.entry.createdAt,
                        draft,
                        current.entry.newest,
                    ),
            )
        val proposal = seed(f, f.queued(), indexed, f.anchors())
        assertEquals(draft, proposal.entry.draft)
        assertEquals(indexed.entry, proposal.entry)
        assertNull(proposal.draft)
    }

    @Test
    fun anOlderUnseenOriginalDoesNotInheritNewerRowRecency() {
        val f = SpaceInboxCommandFixture()
        val current = f.source.inboxState
        val newer =
            f.anchor()
                .copy(
                    source = CollaborationSpaceInboxSource.GroupOriginal(f.id(50)),
                    position =
                        f.anchor().position.copy(timestamp = f.anchor().position.timestamp + 60_000),
                )
        val indexed =
            current.copy(
                entry =
                    collaborationSpaceInboxEntry(
                        current.entry!!.conversationId,
                        current.entry.createdAt,
                        current.draft,
                        newer,
                    ),
                candidates = listOf(newer),
            )
        val proposal =
            seed(
                f,
                f.queued(),
                indexed,
                CollaborationSpaceInboxActivities(indexed.version, emptyList()),
            )
        assertEquals(newer, proposal.entry.newest)
        assertTrue(proposal.activities.single().position.timestamp < newer.position.timestamp)
        assertEquals(
            current.version!!.lastOrdinal + 1,
            proposal.activities.single().position.ordinal,
        )
    }

    @Test
    fun disagreeingAnchorsOrConversationIdentityCannotOverwriteTheIndex() {
        val f = SpaceInboxCommandFixture()
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxSeedPublication(
                f.access,
                f.source.base.canonical!!,
                f.queued(),
                f.source.inboxState,
                CollaborationSpaceInboxActivities(f.source.inboxState.version, emptyList()),
            )
        )
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxSeedPublication(
                f.access,
                f.source.base.canonical!!.copy(id = f.id(55)),
                f.queued(),
                f.source.inboxState,
                f.anchors(),
            )
        )
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxSeedPublication(
                f.access,
                f.source.base.canonical!!.copy(createdAt = Instant.fromEpochMilliseconds(1)),
                f.queued(),
                f.source.inboxState,
                f.anchors(),
            )
        )
    }

    @Test
    fun accountMismatchAndMalformedCommandsAreRejectedBeforePublication() {
        val f = SpaceInboxCommandFixture()
        for (record in
            listOf(
                f.queued().copy(revision = -1),
                f.queued()
                    .copy(
                        intent =
                            f.queued()
                                .intent
                                .copy(owner = f.access.messageOwner().copy(accountId = f.id(77)))
                    ),
            )) {
            assertIs<AppResult.Failed>(
                collaborationSpaceInboxSeedPublication(
                    f.access,
                    f.source.base.canonical!!,
                    record,
                    empty(),
                    CollaborationSpaceInboxActivities(null, emptyList()),
                )
            )
        }
        val changed = f.anchors().copy(version = f.anchors().version!!.copy(revision = 1))
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxSeedPublication(
                        f.access,
                        f.source.base.canonical!!,
                        f.queued(),
                        f.source.inboxState,
                        changed,
                    )
                )
                .failure
                .kind,
        )
    }

    @Test
    fun exhaustedInsertionOrderRejectsNewAnchorsButAllowsExactExistingOnes() {
        val f = SpaceInboxCommandFixture()
        val current =
            f.source.inboxState.copy(
                version =
                    f.source.inboxState.version!!.copy(
                        lastOrdinal = COLLABORATION_SPACE_INBOX_MAX_ORDER
                    )
            )
        assertEquals(
            COLLABORATION_SPACE_INBOX_MAX_ORDER,
            seed(f, f.queued(), current, f.anchors().copy(version = current.version)).lastOrdinal,
        )
        val unseen = f.queued().copy(intent = f.queued().intent.copy(id = f.id(80)))
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxSeedPublication(
                        f.access,
                        f.source.base.canonical!!,
                        unseen,
                        current,
                        CollaborationSpaceInboxActivities(current.version, emptyList()),
                    )
                )
                .failure
                .kind,
        )
    }

    private fun empty() = CollaborationSpaceInboxConversation(null, null, null, emptyList())

    private fun seed(
        f: SpaceInboxCommandFixture,
        record: CollaborationSpaceMessageRecord,
        current: CollaborationSpaceInboxConversation,
        anchors: CollaborationSpaceInboxActivities,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceInboxPublication>>(
                collaborationSpaceInboxSeedPublication(
                    f.access,
                    f.source.base.canonical!!,
                    record,
                    current,
                    anchors,
                )
            )
            .value
}
