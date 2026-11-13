package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxCommandPolicyTest {
    @Test
    fun sentEvidencePreservesANewerEditedPreviewTimeAndPlacement() {
        val f = SpaceInboxCommandFixture()
        val original = f.anchor()
        val edited = original.copy(preview = original.preview!!.copy(text = "A newer history edit"))
        f.replaceAnchor(edited)
        val publication = success(f.policy())
        val confirmed = publication.activities.single()
        assertEquals(
            edited.copy(
                preview = edited.preview!!.copy(delivery = CollaborationMessageDelivery.SENT)
            ),
            confirmed,
        )
        assertEquals(f.source.inboxState.entry!!.position, publication.entry.position)
        assertEquals(f.source.inboxState.version!!.lastOrdinal, publication.lastOrdinal)
        assertNull(publication.draft)
        assertEquals(CollaborationSpaceInboxRepairChange.Complete(f.reference), publication.repair)
    }

    @Test
    fun anEarlierAcknowledgementCannotReplaceANewerPendingConversationPreview() {
        val f = SpaceInboxCommandFixture()
        val previous = f.anchor()
        val newer =
            previous.copy(
                source = CollaborationSpaceInboxSource.GroupOriginal(f.id(20)),
                position = previous.position.copy(ordinal = 3),
                preview = previous.preview!!.copy(text = "Newer pending"),
            )
        f.source.inboxState =
            f.source.inboxState.copy(version = f.source.inboxState.version!!.copy(lastOrdinal = 3))
        f.replaceAnchor(newer)
        val publication = success(f.policy())
        assertEquals(newer, publication.entry.newest)
        assertEquals(newer.position, publication.entry.position)
        assertEquals(
            CollaborationMessageDelivery.SENT,
            publication.activities.single().preview?.delivery,
        )
    }

    @Test
    fun cancelledNewestRevealsTheEarlierCandidateAndRetainsTyping() {
        val f = SpaceInboxCommandFixture()
        val current = f.anchor()
        val earlier =
            current.copy(
                source = CollaborationSpaceInboxSource.GroupOriginal(f.id(20)),
                position =
                    current.position.copy(timestamp = current.position.timestamp - 1, ordinal = 3),
            )
        f.replaceAnchor(earlier)
        val typing =
            CollaborationSpaceInboxDraft(
                f.source.original.copy(revision = f.id(21), text = "Typing"),
                current.position.copy(
                    timestamp = f.source.original.savedAt.toEpochMilliseconds(),
                    ordinal = 4,
                ),
            )
        f.source.inboxState =
            f.source.inboxState.copy(
                version = f.source.inboxState.version!!.copy(lastOrdinal = 4),
                draft = typing,
                entry =
                    collaborationSpaceInboxEntry(
                        f.source.original.conversationId,
                        f.source.inboxState.entry!!.createdAt,
                        typing,
                        current,
                    ),
            )
        val publication =
            success(f.policy(f.queued().copy(state = CollaborationMessageState.Cancelled)))
        assertEquals(earlier, publication.entry.newest)
        assertEquals(typing, publication.entry.draft)
        assertNull(publication.activities.single().preview)
        assertEquals(current.position, publication.activities.single().position)
    }

    @Test
    fun aHiddenAnchorCannotBeRevivedByLateSentEvidence() {
        val f = SpaceInboxCommandFixture()
        val hidden = f.anchor().copy(preview = null)
        f.replaceAnchor(hidden)
        val publication = success(f.policy())
        assertEquals(hidden, publication.activities.single())
        assertNull(publication.entry.newest)
    }

    @Test
    fun cancelledEvidenceCannotEraseAnAlreadyConfirmedOriginal() {
        val f = SpaceInboxCommandFixture()
        val original = f.anchor()
        f.replaceAnchor(
            original.copy(
                preview = original.preview!!.copy(delivery = CollaborationMessageDelivery.SENT)
            )
        )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    f.policy(f.queued().copy(state = CollaborationMessageState.Cancelled))
                )
                .failure
                .kind,
        )
    }

    @Test
    fun aDifferentIntentOrNamespaceCannotConfirmAnOriginalWithTheSameId() {
        val f = SpaceInboxCommandFixture()
        for (record in
            listOf(
                f.sent().copy(intent = f.sent().intent.copy(text = "Different body")),
                f.sent()
                    .copy(
                        intent =
                            f.sent()
                                .intent
                                .copy(owner = f.sent().intent.owner.copy(spaceId = f.id(40)))
                    ),
            )) {
            assertIs<AppResult.Failed>(
                captureCollaborationSpaceInboxCommand(f.access, f.reference, record)
            )
        }
        val companyAnchor =
            f.anchor()
                .copy(
                    source =
                        CollaborationSpaceInboxSource.CompanyOriginal(
                            f.id(40),
                            f.source.original.conversationId,
                            f.source.submission.id,
                        )
                )
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxCommandPublication(
                f.access,
                f.reference,
                f.sent(),
                f.source.inboxState,
                CollaborationSpaceInboxActivities(
                    f.source.inboxState.version,
                    listOf(companyAnchor),
                ),
            )
        )
    }

    @Test
    fun absentAndNonterminalRecordsDoNotBecomeDeliveryReceiptsAndCapturedSetsAreDetached() {
        val f = SpaceInboxCommandFixture()
        assertEquals(
            AppResult.Success(null),
            captureCollaborationSpaceInboxCommand(f.access, f.reference, null),
        )
        val sent = f.sent().state as CollaborationMessageState.Sent
        val before = mutableSetOf("existing-transaction")
        val staging =
            f.queued()
                .copy(
                    revision = 1,
                    state = CollaborationMessageState.Staging(sent.device, before, sent.origin),
                )
        val captured =
            assertNotNull(
                success(captureCollaborationSpaceInboxCommand(f.access, f.reference, staging))
            )
        before.clear()
        assertEquals(
            setOf("existing-transaction"),
            (captured.state as CollaborationMessageState.Staging).before,
        )
        assertIs<AppResult.Failed>(f.policy(f.queued()))
    }

    @Test
    fun changedIndexVersionOrMissingExactAnchorCannotCompleteAReference() {
        val f = SpaceInboxCommandFixture()
        val changed = f.anchors().copy(version = f.source.inboxState.version!!.copy(revision = 1))
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxCommandPublication(
                        f.access,
                        f.reference,
                        f.sent(),
                        f.source.inboxState,
                        changed,
                    )
                )
                .failure
                .kind,
        )
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxCommandPublication(
                f.access,
                f.reference,
                f.sent(),
                f.source.inboxState,
                f.anchors().copy(values = emptyList()),
            )
        )
    }

    private fun <T> success(value: AppResult<T>) = assertIs<AppResult.Success<T>>(value).value
}
