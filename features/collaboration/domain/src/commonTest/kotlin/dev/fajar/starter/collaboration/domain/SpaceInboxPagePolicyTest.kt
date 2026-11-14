package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*
import kotlin.time.Instant

class SpaceInboxPagePolicyTest {
    @Test
    fun requestRejectsMalformedOwnersAndUnboundedPages() {
        val f = SpaceInboxPageFixture()
        assertTrue(validCollaborationSpaceInboxPageRequest(f.access, null, 50))
        assertFalse(
            validCollaborationSpaceInboxPageRequest(f.access.copy(spaceId = "wrong"), null, 50)
        )
        for (limit in listOf(0, -1, 51, Int.MAX_VALUE)) assertFalse(
            validCollaborationSpaceInboxPageRequest(f.access, null, limit)
        )
        for (cursor in listOf("", "x".repeat(4097))) assertFalse(
            validCollaborationSpaceInboxPageRequest(f.access, cursor, 50)
        )
    }

    @Test
    fun absenceAndRestartHaveDifferentCursorMeaning() {
        val f = SpaceInboxPageFixture()
        val absent = CollaborationSpaceInboxPage.Items(null, emptyList(), null)
        assertTrue(validCollaborationSpaceInboxPage(f.access, null, 50, absent))
        assertFalse(validCollaborationSpaceInboxPage(f.access, "cursor", 50, absent))
        assertFalse(
            validCollaborationSpaceInboxPage(
                f.access,
                null,
                50,
                CollaborationSpaceInboxPage.Restart,
            )
        )
        assertTrue(
            validCollaborationSpaceInboxPage(
                f.access,
                "cursor",
                50,
                CollaborationSpaceInboxPage.Restart,
            )
        )
        assertFalse(
            validCollaborationSpaceInboxPage(
                f.access,
                null,
                50,
                absent.copy(entries = listOf(f.entry())),
            )
        )
    }

    @Test
    fun continuationRequiresFullPageAndNeverRepeatsInput() {
        val f = SpaceInboxPageFixture()
        val page = f.page().copy(next = "next")
        assertTrue(validCollaborationSpaceInboxPage(f.access, "before", 1, page))
        assertFalse(validCollaborationSpaceInboxPage(f.access, "next", 1, page))
        assertFalse(validCollaborationSpaceInboxPage(f.access, "before", 2, page))
        assertFalse(validCollaborationSpaceInboxPage(f.access, "before", 1, page.copy(next = "")))
        assertTrue(validCollaborationSpaceInboxPage(f.access, "before", 1, page.copy(next = null)))
    }

    @Test
    fun duplicateAndReversedRowsAreRejectedWithoutReordering() {
        val f = SpaceInboxPageFixture()
        val recent = f.entry(timestamp = 2000, ordinal = 2)
        val old = f.entry(f.other)
        val page = f.page().copy(entries = listOf(recent, old))
        assertTrue(validCollaborationSpaceInboxPage(f.access, null, 2, page))
        assertFalse(
            validCollaborationSpaceInboxPage(
                f.access,
                null,
                2,
                page.copy(entries = listOf(old, recent)),
            )
        )
        assertFalse(
            validCollaborationSpaceInboxPage(
                f.access,
                null,
                2,
                page.copy(entries = listOf(recent, recent)),
            )
        )
        assertFalse(validCollaborationSpaceInboxPage(f.access, null, 1, page))
    }

    @Test
    fun checkpointAndRetainedOrdinalMustCoverEveryRow() {
        val f = SpaceInboxPageFixture()
        val page = f.page()
        for (version in
            listOf(
                f.version.copy(generation = "bad"),
                f.version.copy(revision = -1),
                f.version.copy(lastOrdinal = 0),
            )) assertFalse(
            validCollaborationSpaceInboxPage(f.access, null, 50, page.copy(version = version))
        )
        assertFalse(
            validCollaborationSpaceInboxPage(
                f.access,
                null,
                50,
                page.copy(
                    entries =
                        listOf(f.entry().copy(position = CollaborationSpaceInboxPosition(2000, 2)))
                ),
            )
        )
    }

    @Test
    fun frozenOrForeignDraftCannotBecomeAnInboxPreview() {
        val f = SpaceInboxPageFixture()
        val draft =
            CollaborationSpaceInboxDraft(
                CollaborationDraft(
                    f.id,
                    f.generation,
                    "Draft",
                    Instant.fromEpochMilliseconds(2000),
                ),
                CollaborationSpaceInboxPosition(2000, 2),
            )
        val row = collaborationSpaceInboxEntry(f.id, 100, draft, f.entry().newest)
        assertTrue(
            validCollaborationSpaceInboxPage(
                f.access,
                null,
                50,
                f.page().copy(entries = listOf(row)),
            )
        )
        for (bad in
            listOf(
                draft.copy(value = draft.value.copy(submissionId = f.other)),
                draft.copy(value = draft.value.copy(conversationId = f.other)),
            )) assertFalse(
            validCollaborationSpaceInboxPage(
                f.access,
                null,
                50,
                f.page().copy(entries = listOf(row.copy(draft = bad))),
            )
        )
    }

    @Test
    fun pendingDeliveryAndDraftRemainIndependentOfAcknowledgement() {
        val f = SpaceInboxPageFixture()
        val pending = f.entry()
        val newest = requireNotNull(pending.newest)
        val sent =
            pending.copy(
                newest =
                    newest.copy(
                        preview =
                            requireNotNull(newest.preview)
                                .copy(delivery = CollaborationMessageDelivery.SENT)
                    )
            )
        assertEquals(pending.position, sent.position)
        for (row in listOf(pending, sent)) assertTrue(
            validCollaborationSpaceInboxPage(
                f.access,
                null,
                50,
                f.page().copy(entries = listOf(row)),
            )
        )
    }

    @Test
    fun unavailableOrDeletedContentCannotLeakAStoredBody() {
        val f = SpaceInboxPageFixture()
        val row = f.entry()
        val newest = requireNotNull(row.newest)
        val invalid =
            row.copy(
                newest =
                    newest.copy(
                        preview =
                            requireNotNull(newest.preview)
                                .copy(kind = CollaborationSpaceInboxContent.DELETED)
                    )
            )
        assertFalse(
            validCollaborationSpaceInboxPage(
                f.access,
                null,
                50,
                f.page().copy(entries = listOf(invalid)),
            )
        )
        val hidden = row.copy(newest = newest.copy(preview = null))
        assertFalse(
            validCollaborationSpaceInboxPage(
                f.access,
                null,
                50,
                f.page().copy(entries = listOf(hidden)),
            )
        )
    }
}
