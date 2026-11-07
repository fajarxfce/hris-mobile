package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*
import kotlin.time.Instant

class CollaborationOriginalConversationPolicyTest {
    private val account = "10000000-0000-4000-8000-000000000001"
    private val peer = "20000000-0000-4000-8000-000000000001"
    private val owner =
        CollaborationSpaceOwner(
            CollaborationNativeParent("local", "https://example.test/api/v1/", account, peer),
            "30000000-0000-4000-8000-000000000001",
        )
    private val position =
        CollaborationOriginalConversationPosition(
            "40000000-0000-4000-8000-000000000001",
            "50000000-0000-4000-8000-000000000001",
        )
    private val original =
        CollaborationSpaceOriginalConversation(
            owner,
            CollaborationOriginalConversation(
                position,
                account,
                peer,
                account,
                Instant.parse("2026-11-07T00:00:00.000001Z"),
                0,
            ),
            null,
        )

    @Test
    fun nilIdentifiersCannotBecomeOriginalCoordinatesOrOwners() {
        val nil = "00000000-0000-0000-0000-000000000000"
        for (value in listOf(position.copy(companyId = nil), position.copy(conversationId = nil))) {
            assertFalse(validCollaborationOriginalConversationPosition(value))
            assertFalse(validCollaborationOriginalConversationQuery(owner, value, 1))
            assertFalse(
                validCollaborationSpaceOriginalConversation(
                    original.copy(conversation = original.conversation.copy(position = value)),
                    owner,
                )
            )
        }
        for (value in
            listOf(
                owner.copy(spaceId = nil),
                owner.copy(parent = owner.parent.copy(accountId = nil)),
                owner.copy(parent = owner.parent.copy(nativeSessionId = nil)),
            )) {
            assertFalse(validCollaborationOriginalConversationOwner(value))
            assertFalse(
                validCollaborationSpaceOriginalConversation(original.copy(owner = value), value)
            )
        }
        assertFalse(
            validCollaborationSpaceOriginalConversation(
                original.copy(conversation = original.conversation.copy(firstAccountId = nil)),
                owner,
            )
        )
    }

    @Test
    fun logicalOwnerAndOriginalCoordinateBothHaveToMatch() {
        assertTrue(validCollaborationSpaceOriginalConversation(original, owner, position))
        assertFalse(
            validCollaborationSpaceOriginalConversation(
                original,
                owner.copy(spaceId = peer),
                position,
            )
        )
        assertFalse(
            validCollaborationSpaceOriginalConversation(
                original,
                owner.copy(parent = owner.parent.copy(nativeSessionId = account)),
                position,
            )
        )
        assertFalse(
            validCollaborationSpaceOriginalConversation(
                original,
                owner,
                position.copy(companyId = owner.spaceId),
            )
        )
        assertFalse(
            validCollaborationSpaceOriginalConversation(
                original.copy(
                    conversation = original.conversation.copy(createdBy = position.companyId)
                ),
                owner,
            )
        )
    }

    @Test
    fun exactFiniteMicrosecondTimesAndSeparateRoomCreatorArePreserved() {
        for (at in listOf("0001-01-01T00:00:00Z", "9999-12-31T23:59:59.999999Z")) {
            val time = Instant.parse(at)
            val value =
                original.copy(
                    conversation = original.conversation.copy(createdAt = time),
                    room =
                        CollaborationOriginalRoom(
                            "[2001:db8::1]:8448",
                            "!owned:example.test",
                            time,
                            peer,
                        ),
                )
            assertTrue(validCollaborationSpaceOriginalConversation(value, owner))
        }
        for (at in
            listOf(
                "0000-12-31T23:59:59Z",
                "+10000-01-01T00:00:00Z",
                "2026-11-07T00:00:00.000000001Z",
            )) {
            assertFalse(
                validCollaborationSpaceOriginalConversation(
                    original.copy(
                        conversation = original.conversation.copy(createdAt = Instant.parse(at))
                    ),
                    owner,
                )
            )
        }
    }

    @Test
    fun compositeProgressRetainsSameIdAcrossCompaniesButRejectsCursorLoops() {
        val next =
            original.copy(
                conversation =
                    original.conversation.copy(
                        position = position.copy(companyId = "40000000-0000-4000-8000-000000000002")
                    )
            )
        val page =
            CollaborationSpaceOriginalConversationPage(
                owner,
                listOf(original, next),
                next.conversation.position,
            )
        assertTrue(validCollaborationSpaceOriginalConversationPage(page, owner, null, 2))
        assertTrue(
            validCollaborationSpaceOriginalConversationPage(
                page.copy(items = listOf(next), next = null),
                owner,
                position,
                1,
            )
        )
        assertFalse(validCollaborationSpaceOriginalConversationPage(page, owner, position, 2))
        assertFalse(
            validCollaborationSpaceOriginalConversationPage(
                page.copy(next = position),
                owner,
                null,
                2,
            )
        )
        assertFalse(
            validCollaborationSpaceOriginalConversationPage(
                page.copy(items = page.items.reversed()),
                owner,
                null,
                2,
            )
        )
        assertTrue(
            validCollaborationSpaceOriginalConversationPage(
                page.copy(items = emptyList(), next = null),
                owner,
                position,
                2,
            )
        )
    }

    @Test
    fun realmPolicyRemainsMetadataOnlyAndRejectsInvalidExplicitPorts() {
        for (value in
            listOf(
                "matrix.example.test",
                "matrix.example.test:8448",
                "[2001:db8::1]",
                "[2001:db8::1]:443",
            )) assertTrue(validCollaborationSpaceServerName(value))
        for (value in
            listOf(
                "https://example.test",
                "example.test:0",
                "example.test:65536",
                "example.test:",
                "example.test:abc",
                "[2001:db8::1]:0",
                "[invalid]",
                ":443",
                "example.test/path",
                "example.test\n",
            )) assertFalse(validCollaborationSpaceServerName(value))
    }
}
