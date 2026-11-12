package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days

class CollaborationSpaceHistoryLocationTest {
    @Test
    fun spacePublicationKeepsCanonicalCoordinatesAndRoom() {
        val f = SpaceRoomBindingPreparationFixture()
        assertEquals(
            AppResult.Success(
                CollaborationSpaceHistoryLocation.Space(f.access, f.conversation.id, f.roomId)
            ),
            resolveCollaborationSpaceHistoryLocation(f.access, f.conversation.id, f.binding),
        )
        assertEquals(0, f.bindingReads)
        assertTrue(f.bindingWrites.isEmpty() && f.originalReads.isEmpty())
    }

    @Test
    fun legacyPublicationUsesOriginalCompanyAndConversationWithoutRelabeling() {
        val f = SpaceRoomBindingPreparationFixture(legacy = true)
        assertNotEquals(f.access.spaceId, f.position.companyId)
        assertNotEquals(f.conversation.id, f.position.conversationId)
        val found =
            assertIs<AppResult.Success<CollaborationSpaceHistoryLocation>>(
                    resolveCollaborationSpaceHistoryLocation(f.access, f.conversation.id, f.binding)
                )
                .value
        assertEquals(
            CollaborationSpaceHistoryLocation.Original(
                CollaborationLocalAccess(
                    f.access.sessionId,
                    f.access.audience,
                    f.access.accountId,
                    f.position.companyId,
                ),
                f.position.conversationId,
                f.roomId,
            ),
            found,
        )
        assertTrue(f.originalReads.isEmpty())
    }

    @Test
    fun equalIdentifierValuesNeverCollapseTheTwoStorageNamespaces() {
        val f = SpaceRoomBindingPreparationFixture(legacy = true)
        val position =
            CollaborationOriginalConversationPosition(f.access.spaceId, f.conversation.id)
        val original = requireNotNull(f.binding.original)
        val binding =
            f.binding.copy(
                room =
                    f.binding.room.copy(
                        original = requireNotNull(f.binding.room.original).copy(position = position)
                    ),
                original =
                    original.copy(conversation = original.conversation.copy(position = position)),
            )
        val found =
            assertIs<AppResult.Success<CollaborationSpaceHistoryLocation>>(
                    resolveCollaborationSpaceHistoryLocation(f.access, f.conversation.id, binding)
                )
                .value
        val selected = assertIs<CollaborationSpaceHistoryLocation.Original>(found)
        assertEquals(position.companyId, selected.access.companyId)
        assertEquals(position.conversationId, selected.conversationId)
    }

    @Test
    fun missingBindingIsUnavailableInsteadOfAnEmptyOrGuessedHistory() {
        val f = SpaceRoomBindingPreparationFixture()
        val result =
            assertIs<AppResult.Failed>(
                resolveCollaborationSpaceHistoryLocation(f.access, f.conversation.id, null)
            )
        assertEquals(FailureKind.Unavailable, result.failure.kind)
        assertEquals("client.collaboration_conversation_unavailable", result.failure.code)
    }

    @Test
    fun malformedRequestAndAccountCoordinatesAreRejectedBeforeBindingSelection() {
        val f = SpaceRoomBindingPreparationFixture()
        val invalid =
            listOf(
                f.access.copy(sessionId = ""),
                f.access.copy(sessionId = "bad session"),
                f.access.copy(audience = "bad audience"),
                f.access.copy(accountId = "not-an-id"),
                f.access.copy(spaceId = "not-an-id"),
            )
        for (access in invalid) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(
                        resolveCollaborationSpaceHistoryLocation(
                            access,
                            f.conversation.id,
                            f.binding,
                        )
                    )
                    .failure
                    .kind,
            )
        }
        for (id in listOf("", " ", "not-an-id", "a".repeat(129))) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(
                        resolveCollaborationSpaceHistoryLocation(f.access, id, f.binding)
                    )
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun anotherCanonicalConversationSpaceOrParticipantCannotSelectHistory() {
        val f = SpaceRoomBindingPreparationFixture()
        val other = "f0000000-0000-4000-8000-000000000001"
        val invalid =
            listOf(
                f.binding.copy(conversation = f.binding.conversation.copy(id = other)),
                f.binding.copy(conversation = f.binding.conversation.copy(spaceId = other)),
            )
        for (binding in invalid) {
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(
                        resolveCollaborationSpaceHistoryLocation(
                            f.access,
                            f.conversation.id,
                            binding,
                        )
                    )
                    .failure
                    .kind,
            )
        }
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(
                    resolveCollaborationSpaceHistoryLocation(
                        f.access.copy(accountId = other),
                        f.conversation.id,
                        f.binding,
                    )
                )
                .failure
                .kind,
        )
    }

    @Test
    fun incompleteAndMismatchedOriginalFactsNeverFallBackToSpaceHistory() {
        val f = SpaceRoomBindingPreparationFixture(legacy = true)
        val original = requireNotNull(f.binding.original)
        val invalid =
            listOf(
                f.binding.copy(original = null),
                f.binding.copy(room = f.binding.room.copy(original = null)),
                f.binding.copy(room = f.binding.room.copy(roomId = "!another:matrix.example.test")),
                f.binding.copy(
                    original =
                        original.copy(room = original.room.copy(serverName = "other.example.test"))
                ),
                f.binding.copy(
                    original =
                        original.copy(
                            conversation =
                                original.conversation.copy(
                                    position =
                                        f.position.copy(
                                            companyId = "f0000000-0000-4000-8000-000000000001"
                                        )
                                )
                        )
                ),
            )
        for (binding in invalid) {
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(
                        resolveCollaborationSpaceHistoryLocation(
                            f.access,
                            f.conversation.id,
                            binding,
                        )
                    )
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun spacePublicationCannotCarryLegacyFactsOrMalformedProviderMetadata() {
        val f = SpaceRoomBindingPreparationFixture()
        val legacy = SpaceRoomBindingPreparationFixture(legacy = true)
        for (binding in
            listOf(
                f.binding.copy(original = legacy.binding.original),
                f.binding.copy(room = f.binding.room.copy(original = legacy.binding.room.original)),
                f.binding.copy(room = f.binding.room.copy(serverName = "invalid server")),
            )) {
            assertIs<AppResult.Failed>(
                resolveCollaborationSpaceHistoryLocation(f.access, f.conversation.id, binding)
            )
        }
    }

    @Test
    fun oldBindingAndExpiredNativeProofDoNotChangeLocalCoordinates() {
        val f = SpaceRoomBindingPreparationFixture(legacy = true)
        val before = f.binding
        val expected = resolveCollaborationSpaceHistoryLocation(f.access, f.conversation.id, before)
        f.native.setup.now += 365.days
        assertEquals(
            expected,
            resolveCollaborationSpaceHistoryLocation(f.access, f.conversation.id, before),
        )
        assertEquals(before, f.binding)
        assertTrue(
            f.bindingWrites.isEmpty() && f.originalReads.isEmpty() && f.scheduleRequests.isEmpty()
        )
    }
}
