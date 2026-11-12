package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class SpaceThreadCommandPolicyTest {
    @Test
    fun canonicalCommandsRetainEveryStateAndOriginalLogicalIdentity() {
        val f = SpaceThreadHistoryFixture(true)
        val record = f.commands.spaceRecord()
        val device = f.commands.device()
        val origin = CollaborationMessageOrigin("@self:matrix.example.test", "a".repeat(64))
        for (state in
            listOf(
                CollaborationMessageState.Queued,
                CollaborationMessageState.Cancelled,
                CollaborationMessageState.Staging(device, setOf("previous"), origin),
                CollaborationMessageState.Staged(device, "tx", origin),
                CollaborationMessageState.Sent(device, "tx", "\$sent", origin),
            )) {
            val current = record.copy(revision = if (state.origin == null) 0 else 1, state = state)
            assertEquals(
                AppResult.Success(listOf(current)),
                collaborationSpaceThreadCommands(f.access, f.id, listOf(current)),
            )
        }
    }

    @Test
    fun originalCommandsKeepLegacyMissingOriginAndCompanyCoordinates() {
        val f = SpaceThreadHistoryFixture(true)
        val location = originalLocation(f)
        val record = f.commands.originalRecord()
        val device = f.commands.device()
        for (state in
            listOf(
                CollaborationMessageState.Queued,
                CollaborationMessageState.Cancelled,
                CollaborationMessageState.Staging(device, setOf("previous")),
                CollaborationMessageState.Staged(device, "tx"),
                CollaborationMessageState.Sent(device, "tx", "\$sent"),
            )) {
            val current = record.copy(revision = 1, state = state)
            assertEquals(
                AppResult.Success(listOf(current)),
                collaborationSpaceOriginalThreadCommands(location, listOf(current)),
            )
        }
        assertNotEquals(f.id, location.conversationId)
    }

    @Test
    fun foreignDuplicateOversizedAndMalformedCanonicalFactsAreRejected() {
        val f = SpaceThreadHistoryFixture()
        val record = f.commands.spaceRecord()
        for (records in
            listOf(
                listOf(
                    record.copy(
                        intent = record.intent.copy(conversationId = f.native.contact.accountId)
                    )
                ),
                listOf(
                    record.copy(
                        intent =
                            record.intent.copy(
                                owner =
                                    record.intent.owner.copy(accountId = f.native.contact.accountId)
                            )
                    )
                ),
                listOf(record, record),
                List(129) { record },
                listOf(record.copy(intent = record.intent.copy(text = ""))),
                listOf(record.copy(revision = -1)),
            )) assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(collaborationSpaceThreadCommands(f.access, f.id, records))
                .failure
                .kind,
        )
    }

    @Test
    fun originalFactsCannotBorrowCanonicalOwnerOrConversation() {
        val f = SpaceThreadHistoryFixture(true)
        val record = f.commands.originalRecord()
        for (records in
            listOf(
                listOf(record.copy(intent = record.intent.copy(conversationId = f.id))),
                listOf(
                    record.copy(
                        intent =
                            record.intent.copy(
                                owner =
                                    record.intent.owner.copy(accountId = f.native.contact.accountId)
                            )
                    )
                ),
                listOf(record, record),
                List(129) { record },
                listOf(record.copy(intent = record.intent.copy(text = ""))),
                listOf(record.copy(revision = -1)),
                listOf(
                    record.copy(
                        state =
                            CollaborationMessageState.Staged(
                                f.commands.device(),
                                "invalid transaction",
                            )
                    )
                ),
            )) assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(
                    collaborationSpaceOriginalThreadCommands(originalLocation(f), records)
                )
                .failure
                .kind,
        )
    }

    @Test
    fun stagingBaselinesAndCallerListsAreDetachedInBothNamespaces() {
        val f = SpaceThreadHistoryFixture(true)
        val before = mutableSetOf("previous")
        val origin = CollaborationMessageOrigin("@self:matrix.example.test", "a".repeat(64))
        val state = CollaborationMessageState.Staging(f.commands.device(), before, origin)
        val space = mutableListOf(f.commands.spaceRecord().copy(revision = 1, state = state))
        val original = mutableListOf(f.commands.originalRecord().copy(revision = 1, state = state))
        val capturedSpace =
            assertIs<AppResult.Success<List<CollaborationSpaceMessageRecord>>>(
                    collaborationSpaceThreadCommands(f.access, f.id, space)
                )
                .value
        val capturedOriginal =
            assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                    collaborationSpaceOriginalThreadCommands(originalLocation(f), original)
                )
                .value
        before += "changed"
        space.clear()
        original.clear()
        assertEquals(
            setOf("previous"),
            assertIs<CollaborationMessageState.Staging>(capturedSpace.single().state).before,
        )
        assertEquals(
            setOf("previous"),
            assertIs<CollaborationMessageState.Staging>(capturedOriginal.single().state).before,
        )
    }

    @Test
    fun orderUsesOriginalCreationTimeAndIdWithoutDroppingTerminalFacts() {
        val f = SpaceThreadHistoryFixture(true)
        val first = f.commands.spaceRecord()
        val second = f.commands.spaceRecord("b1000000-0000-4000-8000-000000000002")
        val newer =
            first.copy(
                intent =
                    first.intent.copy(
                        id = "b1000000-0000-4000-8000-000000000003",
                        createdAt = first.intent.createdAt + 1.seconds,
                    ),
                state = CollaborationMessageState.Cancelled,
            )
        assertEquals(
            AppResult.Success(listOf(first, second, newer)),
            collaborationSpaceThreadCommands(f.access, f.id, listOf(newer, second, first)),
        )
        val original = f.commands.originalRecord()
        val cancelled =
            original.copy(
                intent = original.intent.copy(id = second.intent.id),
                state = CollaborationMessageState.Cancelled,
            )
        assertEquals(
            AppResult.Success(listOf(original, cancelled)),
            collaborationSpaceOriginalThreadCommands(
                originalLocation(f),
                listOf(cancelled, original),
            ),
        )
    }

    private fun originalLocation(f: SpaceThreadHistoryFixture) =
        assertIs<CollaborationSpaceHistoryLocation.Original>(
            assertIs<AppResult.Success<CollaborationSpaceHistoryLocation>>(
                    resolveCollaborationSpaceHistoryLocation(f.access, f.id, f.room.binding)
                )
                .value
        )
}
