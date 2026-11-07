package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Instant

class CollaborationSpaceConversationCommandPolicyTest {
    private fun id(index: Int) = "b1000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private val access =
        CollaborationSpaceLocalAccess("login", "https://hris.example.test/", id(1), id(2))
    private val open =
        CollaborationSpaceConversationCommand.Open(
            id(3),
            CollaborationSpaceIdentityBinding(
                access.audience,
                access.accountId,
                access.spaceId,
                id(4),
            ),
            id(5),
            Instant.parse("2026-11-07T00:00:00Z"),
        )
    private val room =
        CollaborationSpaceConversationCommand.Room(
            CollaborationSpaceRoomRequestIntent(
                id(6),
                open.binding,
                id(7),
                CollaborationSpaceRoomRequest(
                    id(8),
                    999999,
                    CollaborationSpaceRoomAttempt(open.peerAccountId, 999999),
                ),
            ),
            open.peerAccountId,
            open.createdAt,
        )

    @Test
    fun immutableIntentSurvivesLocalLoginReplacementButNeverChangesItsAccountOrPair() {
        val zero = "00000000-0000-0000-0000-000000000000"
        assertFalse(
            validCollaborationSpaceConversationCommand(
                access.copy(accountId = zero),
                open.copy(binding = open.binding.copy(accountId = zero)),
            )
        )
        assertTrue(
            validCollaborationSpaceConversationCommand(access.copy(sessionId = "replacement"), room)
        )
        assertFalse(
            validCollaborationSpaceConversationCommand(
                access.copy(accountId = open.peerAccountId),
                room,
            )
        )
        assertFalse(validCollaborationSpaceConversationCommand(access.copy(spaceId = id(9)), room))
        for (command in
            listOf(
                open.copy(peerAccountId = access.accountId),
                open.copy(id = "00000000-0000-0000-0000-000000000000"),
                open.copy(binding = open.binding.copy(nativeSessionId = "invalid")),
                room.copy(
                    intent =
                        room.intent.copy(
                            request =
                                room.intent.request.copy(
                                    expectedAttempt = CollaborationSpaceRoomAttempt(id(10), 0)
                                )
                        )
                ),
                room.copy(
                    intent =
                        room.intent.copy(
                            request = room.intent.request.copy(expectedDeviceVersion = 1000000)
                        )
                ),
            )) {
            assertFalse(validCollaborationSpaceConversationCommand(access, command))
        }
    }

    @Test
    fun receiptNeverAcknowledgesADifferentRoomOrReopensConfirmedSetup() {
        val openReceipt =
            CollaborationSpaceConversationOutcome.Recorded(CollaborationSetupReceipt(id(7), 0))
        val roomReceipt =
            CollaborationSpaceConversationOutcome.Recorded(CollaborationSetupReceipt(id(7), 999999))
        val pending =
            CollaborationSpaceConversationCommandRecord(
                open,
                0,
                0,
                CollaborationSpaceConversationOutcome.Pending,
            )
        assertFalse(validCollaborationSpaceConversationCommandTransition(pending, openReceipt, 1))
        assertTrue(
            validCollaborationSpaceConversationCommandRecord(
                access,
                pending.copy(revision = 2, attempts = 1, outcome = openReceipt),
            )
        )
        assertFalse(
            validCollaborationSpaceConversationCommandRecord(
                access,
                pending.copy(revision = 2, attempts = 1, outcome = roomReceipt),
            )
        )
        val recorded = CollaborationSpaceConversationCommandRecord(room, 2, 1, roomReceipt)
        assertTrue(validCollaborationSpaceConversationCommandRecord(access, recorded))
        assertFalse(
            validCollaborationSpaceConversationCommandRecord(
                access,
                recorded.copy(
                    outcome =
                        CollaborationSpaceConversationOutcome.Recorded(
                            CollaborationSetupReceipt(id(10), 0)
                        )
                ),
            )
        )
        assertFalse(
            validCollaborationSpaceConversationCommandTransition(
                recorded,
                CollaborationSpaceConversationOutcome.Unconfirmed(),
                2,
            )
        )
    }

    @Test
    fun attemptsAndRevisionsStayBoundedWithoutLosingUncertainty() {
        val uncertain = CollaborationSpaceConversationOutcome.Unconfirmed()
        val exhausted =
            CollaborationSpaceConversationCommandRecord(
                room,
                9_007_199_254_740_991L,
                1000,
                uncertain,
            )
        assertTrue(validCollaborationSpaceConversationCommandRecord(access, exhausted))
        assertFalse(
            validCollaborationSpaceConversationCommandRecord(
                access,
                exhausted.copy(revision = exhausted.revision + 1),
            )
        )
        assertFalse(
            validCollaborationSpaceConversationCommandTransition(exhausted, uncertain, 1001)
        )
        assertFalse(
            validCollaborationSpaceConversationCommandTransition(
                exhausted,
                CollaborationSpaceConversationOutcome.Pending,
                0,
            )
        )
        assertTrue(
            validCollaborationSpaceConversationCommandTransition(
                exhausted,
                CollaborationSpaceConversationOutcome.Recorded(
                    CollaborationSetupReceipt(id(7), 999999)
                ),
                1000,
            )
        )
    }

    @Test
    fun accessAndUpdateRestrictionsSurviveAnUnsuccessfulRetry() {
        val unavailable = Failure(FailureKind.Network, "Unavailable")
        for (denial in
            listOf(
                Failure(FailureKind.AccessDenied, "Denied"),
                Failure(FailureKind.Unauthorized, "MFA required", code = "mfa_required"),
                Failure(FailureKind.Validation, "Update required", code = "client_update_required"),
            )) {
            val before =
                CollaborationSpaceConversationCommandRecord(
                    room,
                    1,
                    1,
                    CollaborationSpaceConversationOutcome.Unconfirmed(denial),
                )
            assertEquals(denial, retainCollaborationSpaceConversationFailure(denial, unavailable))
            assertFalse(
                validCollaborationSpaceConversationCommandTransition(
                    before,
                    CollaborationSpaceConversationOutcome.Unconfirmed(unavailable),
                    2,
                )
            )
            assertFalse(
                validCollaborationSpaceConversationCommandTransition(
                    before,
                    CollaborationSpaceConversationOutcome.Unconfirmed(),
                    2,
                )
            )
            assertTrue(
                validCollaborationSpaceConversationCommandTransition(
                    before,
                    CollaborationSpaceConversationOutcome.Unconfirmed(denial),
                    2,
                )
            )
        }
    }
}
