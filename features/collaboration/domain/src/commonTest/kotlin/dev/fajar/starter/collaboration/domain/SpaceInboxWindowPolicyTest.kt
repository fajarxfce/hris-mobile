package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class SpaceInboxWindowPolicyTest {
    @Test
    fun requestsBoundAllDirectionsAndRequireCanonicalRefreshIdentity() {
        val f = SpaceInboxPageFixture()
        for (anchor in
            listOf(
                CollaborationSpaceInboxWindowAnchor.Latest,
                CollaborationSpaceInboxWindowAnchor.Conversation(f.id),
                CollaborationSpaceInboxWindowAnchor.Older("older"),
                CollaborationSpaceInboxWindowAnchor.Newer("newer"),
            )) {
            assertTrue(validCollaborationSpaceInboxWindowRequest(f.access, anchor, 50))
            assertFalse(validCollaborationSpaceInboxWindowRequest(f.access, anchor, 51))
            assertFalse(
                validCollaborationSpaceInboxWindowRequest(
                    f.access.copy(spaceId = "bad"),
                    anchor,
                    50,
                )
            )
        }
        assertFalse(
            validCollaborationSpaceInboxWindowRequest(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Conversation("!room:example.test"),
                50,
            )
        )
        assertFalse(
            validCollaborationSpaceInboxWindowRequest(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Newer(""),
                50,
            )
        )
    }

    @Test
    fun anAbsentIndexIsOnlyAnEmptyLatestWindow() {
        val f = SpaceInboxPageFixture()
        val empty = CollaborationSpaceInboxWindow.Items(null, emptyList(), null, null)
        assertTrue(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Latest,
                50,
                empty,
            )
        )
        for (anchor in
            listOf(
                CollaborationSpaceInboxWindowAnchor.Conversation(f.id),
                CollaborationSpaceInboxWindowAnchor.Older("cursor"),
                CollaborationSpaceInboxWindowAnchor.Newer("cursor"),
            )) assertFalse(validCollaborationSpaceInboxWindow(f.access, anchor, 50, empty))
    }

    @Test
    fun aMissingAnchorAndChangedContinuationCannotMasqueradeAsEachOther() {
        val f = SpaceInboxPageFixture()
        assertTrue(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Conversation(f.id),
                50,
                CollaborationSpaceInboxWindow.MissingAnchor,
            )
        )
        assertFalse(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Older("cursor"),
                50,
                CollaborationSpaceInboxWindow.MissingAnchor,
            )
        )
        assertTrue(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Older("cursor"),
                50,
                CollaborationSpaceInboxWindow.Changed,
            )
        )
        assertFalse(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Latest,
                50,
                CollaborationSpaceInboxWindow.Changed,
            )
        )
    }

    @Test
    fun refreshIncludesTheActualVisibleConversation() {
        val f = SpaceInboxPageFixture()
        val page =
            CollaborationSpaceInboxWindow.Items(f.version, listOf(f.entry()), "before", "after")
        assertTrue(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Conversation(f.id),
                50,
                page,
            )
        )
        assertFalse(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Conversation(f.other),
                50,
                page,
            )
        )
        assertFalse(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Conversation(f.id),
                50,
                page.copy(entries = emptyList(), previous = null, next = null),
            )
        )
    }

    @Test
    fun aSingleRowCanContinueBothWaysFromTheSameBoundary() {
        val f = SpaceInboxPageFixture()
        val page =
            CollaborationSpaceInboxWindow.Items(
                f.version,
                listOf(f.entry()),
                "same-boundary",
                "same-boundary",
            )
        assertTrue(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Conversation(f.id),
                1,
                page,
            )
        )
    }

    @Test
    fun aDirectionalWindowCannotRepeatItsOwnContinuation() {
        val f = SpaceInboxPageFixture()
        val page =
            CollaborationSpaceInboxWindow.Items(f.version, listOf(f.entry()), "previous", "next")
        assertFalse(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Older("next"),
                50,
                page,
            )
        )
        assertFalse(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Newer("previous"),
                50,
                page,
            )
        )
        assertTrue(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Older("previous"),
                50,
                page,
            )
        )
        assertTrue(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Newer("next"),
                50,
                page,
            )
        )
    }

    @Test
    fun emptyDirectionalWindowsEndWithoutSyntheticCursors() {
        val f = SpaceInboxPageFixture()
        val empty = CollaborationSpaceInboxWindow.Items(f.version, emptyList(), null, null)
        val anchor = CollaborationSpaceInboxWindowAnchor.Older("cursor")
        assertTrue(validCollaborationSpaceInboxWindow(f.access, anchor, 50, empty))
        assertFalse(
            validCollaborationSpaceInboxWindow(
                f.access,
                anchor,
                50,
                empty.copy(next = "new-cursor"),
            )
        )
        assertFalse(
            validCollaborationSpaceInboxWindow(
                f.access,
                anchor,
                50,
                empty.copy(previous = "new-cursor"),
            )
        )
    }

    @Test
    fun rowValidationPreservesOrderAndRejectsMalformedBounds() {
        val f = SpaceInboxPageFixture()
        val page =
            CollaborationSpaceInboxWindow.Items(
                f.version,
                listOf(f.entry(timestamp = 2000), f.entry(f.other)),
                null,
                null,
            )
        assertTrue(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Latest,
                2,
                page,
            )
        )
        assertFalse(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Latest,
                2,
                page.copy(entries = page.entries.reversed()),
            )
        )
        assertFalse(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Latest,
                2,
                page.copy(previous = "before"),
            )
        )
        assertFalse(
            validCollaborationSpaceInboxWindow(
                f.access,
                CollaborationSpaceInboxWindowAnchor.Latest,
                2,
                page.copy(next = "x".repeat(4097)),
            )
        )
    }
}
