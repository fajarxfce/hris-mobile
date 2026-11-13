package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*

class SpaceInboxPendingEditPolicyTest {
    @Test
    fun actualReplacementNamespaceIsIndependentOfTheOriginalNamespace() {
        val f = Fixture()
        val company =
            CollaborationSpaceInboxEditSource.Company(
                f.companyId,
                f.companyConversation,
                f.operation,
            )
        for (source in listOf(f.edit.source, company)) {
            assertTrue(
                validCollaborationSpaceInboxActivity(
                    f.activity.copy(pendingEdit = f.edit.copy(source = source)),
                    4,
                )
            )
        }
        val original =
            CollaborationSpaceInboxSource.CompanyOriginal(
                f.companyId,
                f.companyConversation,
                f.original,
            )
        assertTrue(validCollaborationSpaceInboxPendingEdit(f.edit, original))
        assertTrue(validCollaborationSpaceInboxPendingEdit(f.edit.copy(source = company), original))
    }

    @Test
    fun replacementIdCannotReuseTheOriginalOrMalformedCoordinates() {
        val f = Fixture()
        for (source in
            listOf(
                CollaborationSpaceInboxEditSource.Group(f.original),
                CollaborationSpaceInboxEditSource.Group("invalid"),
                CollaborationSpaceInboxEditSource.Company(
                    "invalid",
                    f.companyConversation,
                    f.operation,
                ),
                CollaborationSpaceInboxEditSource.Company(f.companyId, "invalid", f.operation),
            )) assertFalse(
            validCollaborationSpaceInboxPendingEdit(f.edit.copy(source = source), f.activity.source)
        )
        assertFalse(
            validCollaborationSpaceInboxPendingEdit(
                f.edit.copy(target = f.target.copy(originalContentHash = "invalid")),
                f.activity.source,
            )
        )
    }

    @Test
    fun aTargetForAnotherOriginalCannotBeAssignedToAnExistingOriginalAnchor() {
        val f = Fixture()
        assertFalse(
            validCollaborationSpaceInboxPendingEdit(
                f.edit.copy(target = f.target.copy(originalLogicalId = f.operation)),
                f.activity.source,
            )
        )
        assertFalse(
            validCollaborationSpaceInboxPendingEdit(
                f.edit.copy(target = f.target.copy(originalLogicalId = null)),
                f.activity.source,
            )
        )
        assertFalse(
            validCollaborationSpaceInboxPendingEdit(
                f.edit,
                CollaborationSpaceInboxSource.GroupOriginal(f.operation),
            )
        )
    }

    @Test
    fun historyTargetsKeepPhysicalRoomSenderAndCompanyCoordinates() {
        val f = Fixture()
        val original =
            CollaborationSpaceInboxSource.History(
                f.companyId,
                f.companyConversation,
                f.target.roomId,
                f.target.sender,
                "\$primary",
            )
        val company =
            f.edit.copy(
                source =
                    CollaborationSpaceInboxEditSource.Company(
                        f.companyId,
                        f.companyConversation,
                        f.operation,
                    )
            )
        assertTrue(validCollaborationSpaceInboxPendingEdit(f.edit, original))
        assertTrue(validCollaborationSpaceInboxPendingEdit(company, original))
        for (wrong in
            listOf(
                original.copy(roomId = "!other:example.test"),
                original.copy(sender = "@other:example.test"),
                original.copy(companyId = f.operation),
                original.copy(conversationId = f.operation),
            )) {
            assertFalse(validCollaborationSpaceInboxPendingEdit(company, wrong))
        }
        assertFalse(
            validCollaborationSpaceInboxPendingEdit(company, original.copy(companyId = null))
        )
    }

    @Test
    fun aliasTargetDoesNotHaveToBeTheHistoryPrimaryEvent() {
        val f = Fixture()
        val history =
            CollaborationSpaceInboxSource.History(
                null,
                f.conversation,
                f.target.roomId,
                f.target.sender,
                "\$primary",
            )
        assertNotEquals(history.eventId, f.target.eventId)
        assertTrue(validCollaborationSpaceInboxPendingEdit(f.edit, history))
    }

    @Test
    fun operationAgeIsNotAnExpiryButInvalidTimesAndRevisionsAreRejected() {
        val f = Fixture()
        assertTrue(
            validCollaborationSpaceInboxPendingEdit(f.edit.copy(createdAt = 0), f.activity.source)
        )
        for (edit in
            listOf(
                f.edit.copy(createdAt = -1),
                f.edit.copy(createdAt = 253_402_300_800_000L),
                f.edit.copy(publishedAtRevision = -1),
                f.edit.copy(publishedAtRevision = COLLABORATION_SPACE_INBOX_MAX_ORDER + 1),
            )) {
            assertFalse(validCollaborationSpaceInboxPendingEdit(edit, f.activity.source))
        }
        assertFalse(validCollaborationSpaceInboxActivity(f.activity, 3))
        assertTrue(validCollaborationSpaceInboxActivity(f.activity, 4))
    }

    @Test
    fun pendingTextCanBeHiddenOrRedactedWithoutRevivingItsPreview() {
        val f = Fixture()
        assertTrue(validCollaborationSpaceInboxActivity(f.activity.copy(preview = null), 4))
        val deleted =
            f.activity.copy(
                preview =
                    f.activity.preview!!.copy(
                        kind = CollaborationSpaceInboxContent.DELETED,
                        text = null,
                    )
            )
        assertTrue(validCollaborationSpaceInboxActivity(deleted, 4))
        for (kind in
            listOf(
                CollaborationSpaceInboxContent.IMAGE,
                CollaborationSpaceInboxContent.FILE,
                CollaborationSpaceInboxContent.STICKER,
            )) {
            assertFalse(
                validCollaborationSpaceInboxActivity(
                    f.activity.copy(preview = f.activity.preview.copy(kind = kind, text = null)),
                    4,
                )
            )
        }
    }

    @Test
    fun editPublicationUsesTheNextRevisionWithoutAllocatingAPlacementOrdinal() {
        val f = Fixture()
        val publication = f.publication()
        assertTrue(validCollaborationSpaceInboxPublication(publication))
        assertEquals(publication.expected!!.lastOrdinal, publication.lastOrdinal)
        assertEquals(f.activity.position, publication.entry.position)
        val future = f.activity.copy(pendingEdit = f.edit.copy(publishedAtRevision = 5))
        assertFalse(validCollaborationSpaceInboxPublication(f.publication(future)))
    }

    @Test
    fun aNestedNewestPreviewCannotCarryProvenanceFromTheFuture() {
        val f = Fixture()
        val future = f.activity.copy(pendingEdit = f.edit.copy(publishedAtRevision = 5))
        val publication =
            f.publication()
                .copy(
                    activities = emptyList(),
                    entry = collaborationSpaceInboxEntry(f.conversation, 0, null, future),
                )
        assertFalse(validCollaborationSpaceInboxPublication(publication))
    }

    @Test
    fun capturedConversationRequiresEditProvenanceUnderItsOwnRevision() {
        val f = Fixture()
        val current =
            CollaborationSpaceInboxConversation(
                f.version.copy(revision = 4),
                collaborationSpaceInboxEntry(f.conversation, 0, null, f.activity),
                null,
                listOf(f.activity),
            )
        assertIs<AppResult.Success<*>>(
            captureCollaborationSpaceInboxConversation(f.conversation, current)
        )
        assertIs<AppResult.Failed>(
            captureCollaborationSpaceInboxConversation(
                f.conversation,
                current.copy(version = f.version),
            )
        )
    }

    @Test
    fun exactComposerAnchorCaptureAlsoRejectsFuturePendingProvenance() {
        val f = Fixture()
        val current =
            CollaborationSpaceInboxActivities(f.version.copy(revision = 4), listOf(f.activity))
        assertIs<AppResult.Success<*>>(
            captureCollaborationSpaceInboxComposerAnchor(f.original, current)
        )
        assertIs<AppResult.Failed>(
            captureCollaborationSpaceInboxComposerAnchor(
                f.original,
                current.copy(version = f.version),
            )
        )
    }

    @Test
    fun diagnosticsDoNotExposeTheTargetOrEitherCommandNamespace() {
        val f = Fixture()
        val company =
            CollaborationSpaceInboxEditSource.Company(
                f.companyId,
                f.companyConversation,
                f.operation,
            )
        for (value in listOf(f.edit, f.edit.source, company)) {
            assertTrue(value.toString().contains("[redacted]"))
            for (identifier in
                listOf(
                    f.original,
                    f.operation,
                    f.companyId,
                    f.target.eventId,
                    f.target.sender,
                )) assertFalse(value.toString().contains(identifier))
        }
    }

    private class Fixture {
        val conversation = id(1)
        val original = id(2)
        val operation = id(3)
        val companyId = id(4)
        val companyConversation = id(5)
        val target =
            CollaborationEditTarget(
                "!room:example.test",
                "\$alias",
                original,
                "@author:example.test",
                "a".repeat(64),
                1000,
                1000,
            )
        val edit =
            CollaborationSpaceInboxPendingEdit(
                CollaborationSpaceInboxEditSource.Group(operation),
                target,
                2000,
                4,
            )
        val activity =
            CollaborationSpaceInboxActivity(
                CollaborationSpaceInboxSource.GroupOriginal(original),
                CollaborationSpaceInboxPosition(1000, 1),
                CollaborationSpaceInboxPreview(
                    CollaborationSpaceInboxContent.TEXT,
                    "Edited text",
                    1000,
                    CollaborationMessageDelivery.SENT,
                ),
                edit,
            )
        val version = CollaborationSpaceInboxVersion(id(6), 3, 1)

        fun publication(value: CollaborationSpaceInboxActivity = activity) =
            CollaborationSpaceInboxPublication(
                version,
                1,
                collaborationSpaceInboxEntry(conversation, 0, null, value),
                activities = listOf(value),
            )

        private fun id(value: Int) = "cf000000-0000-4000-8000-" + value.toString().padStart(12, '0')
    }
}
