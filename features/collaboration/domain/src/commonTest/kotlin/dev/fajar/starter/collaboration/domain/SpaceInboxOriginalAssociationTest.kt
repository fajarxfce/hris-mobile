package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Instant

class SpaceInboxOriginalAssociationTest {
    @Test
    fun stagedOriginalKeepsPendingDisplayAndCannotBecomeAReceipt() {
        val f = Fixture()
        val before = f.group
        val value = assertNotNull(success(f.group()))
        assertEquals(CollaborationSpaceInboxSource.GroupOriginal(f.id), value.source)
        assertEquals(CollaborationMessageDelivery.PENDING, value.delivery)
        assertEquals(f.time.toEpochMilliseconds(), value.timestamp)
        assertEquals(before, f.group)
    }

    @Test
    fun stagingProofAssociatesWithoutInventingAnEnqueueMapping() {
        val f = Fixture()
        val record =
            f.group.copy(
                state = CollaborationMessageState.Staging(f.device, setOf("old"), f.origin)
            )
        assertEquals(
            CollaborationMessageDelivery.PENDING,
            assertNotNull(success(f.group(record))).delivery,
        )
        assertNull(recoverCollaborationSpaceOriginalReceipt(f.access, record, f.history))
    }

    @Test
    fun sentAliasNeedNotBeInThisHistorySnapshotToIdentifyTheSameOriginal() {
        val f = Fixture()
        val record =
            f.group.copy(
                state = CollaborationMessageState.Sent(f.device, "txn", "\$other-alias", f.origin)
            )
        val association = assertNotNull(success(f.group(record)))
        assertEquals(CollaborationMessageDelivery.SENT, association.delivery)
        assertEquals(f.time.toEpochMilliseconds(), association.timestamp)
        assertEquals(CollaborationSpaceInboxSource.GroupOriginal(f.id), association.source)
    }

    @Test
    fun companyOriginalKeepsItsActualCompanyAndConversation() {
        val f = Fixture()
        val value = assertNotNull(success(f.company()))
        assertEquals(
            CollaborationSpaceInboxSource.CompanyOriginal(
                f.companyAccess.companyId,
                f.room.position.conversationId,
                f.id,
            ),
            value.source,
        )
        assertNotEquals(f.binding.conversation.id, f.room.position.conversationId)
        assertEquals(CollaborationMessageDelivery.PENDING, value.delivery)
    }

    @Test
    fun sameUuidInBothNamespacesNeverMakesTheirSourceIdentityEqual() {
        val f = Fixture()
        val group = assertNotNull(success(f.group()))
        val company = assertNotNull(success(f.company()))
        assertNotEquals(group.source, company.source)
        assertEquals(group.timestamp, company.timestamp)
    }

    @Test
    fun sameLogicalIdWithDifferentSenderHashOrExactBodyCannotAssociate() {
        val f = Fixture()
        val variants =
            listOf(
                f.history.copy(sender = "@other:example.test"),
                f.history.copy(contentHash = "b".repeat(64)),
                f.history.copy(text = f.history.text + " "),
                f.history.copy(logicalId = f.otherId),
                f.history.copy(logicalId = null),
            )
        for (history in variants) {
            assertTrue(validCollaborationHistoryMessage(history))
            assertNull(success(f.group(history = history)))
            assertNull(success(f.company(history = history)))
        }
    }

    @Test
    fun anotherRoomDoesNotAssociateEvenWithMatchingSenderAndContent() {
        val f = Fixture()
        val state =
            CollaborationMessageState.Staged(
                f.device.copy(roomId = "!other:example.test"),
                "txn",
                f.origin,
            )
        assertNull(success(f.group(f.group.copy(state = state))))
        assertNull(success(f.company(f.company.copy(state = state))))
    }

    @Test
    fun anotherCanonicalGroupConversationWithTheSameIdIsNotThisOriginal() {
        val f = Fixture()
        assertNull(
            success(f.group(f.group.copy(intent = f.group.intent.copy(conversationId = f.otherId))))
        )
    }

    @Test
    fun foreignAccountOrGroupRecordIsAStorageScopeFailureNotAbsence() {
        val f = Fixture()
        for (owner in
            listOf(
                f.group.intent.owner.copy(accountId = f.otherId),
                f.group.intent.owner.copy(spaceId = f.otherId),
                f.group.intent.owner.copy(audience = "https://other.example.test"),
            )) {
            assertIs<AppResult.Failed>(
                f.group(f.group.copy(intent = f.group.intent.copy(owner = owner)))
            )
        }
    }

    @Test
    fun foreignCompanyAndOriginalConversationCannotReuseTheCanonicalBinding() {
        val f = Fixture()
        for (record in
            listOf(
                f.company.copy(
                    intent =
                        f.company.intent.copy(
                            owner = f.company.intent.owner.copy(companyId = f.otherId)
                        )
                ),
                f.company.copy(
                    intent =
                        f.company.intent.copy(
                            owner = f.company.intent.owner.copy(accountId = f.otherId)
                        )
                ),
            )) assertIs<AppResult.Failed>(f.company(record))
        assertNull(
            success(
                f.company(
                    f.company.copy(intent = f.company.intent.copy(conversationId = f.otherId))
                )
            )
        )
    }

    @Test
    fun missingLegacyOriginDoesNotAcquireProofFromAnAcknowledgedEventId() {
        val f = Fixture()
        val states =
            listOf(
                CollaborationMessageState.Staging(f.device, emptySet()),
                CollaborationMessageState.Staged(f.device, "txn"),
                CollaborationMessageState.Sent(f.device, "txn", f.history.primaryEventId),
            )
        for (state in states) assertNull(success(f.company(f.company.copy(state = state))))
    }

    @Test
    fun newGroupCommandsCannotUseTheLegacyMissingOriginException() {
        val f = Fixture()
        assertIs<AppResult.Failed>(
            f.group(f.group.copy(state = CollaborationMessageState.Staged(f.device, "txn")))
        )
    }

    @Test
    fun queuedAndCancelledCommandsDoNotGainProofFromTheirUuidOrText() {
        val f = Fixture()
        for (state in
            listOf(CollaborationMessageState.Queued, CollaborationMessageState.Cancelled)) {
            assertNull(success(f.group(f.group.copy(revision = 0, state = state))))
            assertNull(success(f.company(f.company.copy(revision = 0, state = state))))
        }
    }

    @Test
    fun redactionAndLocalHidingRetainIdentityWithoutRevivingContent() {
        val f = Fixture()
        for (history in
            listOf(
                f.history.copy(locallyHidden = true),
                f.history.copy(redacted = true, text = null),
            )) {
            val before = history.copy(eventIds = history.eventIds.toList())
            assertEquals(success(f.group()), success(f.group(history = history)))
            assertEquals(success(f.company()), success(f.company(history = history)))
            assertEquals(before, history)
        }
    }

    @Test
    fun retainedEditsDoNotReplaceTheOriginalContentProof() {
        val f = Fixture()
        val edit =
            CollaborationMessageEdit(
                "\$edit",
                f.origin.sender,
                f.history.primaryEventId,
                f.history.timestamp + 1,
                "Changed text",
                "c".repeat(64),
                originalLogicalId = f.id,
            )
        val history =
            f.history.copy(latestEdit = CollaborationMessageRevision(edit, f.history.timestamp))
        assertTrue(validCollaborationHistoryMessage(history))
        assertEquals(success(f.group()), success(f.group(history = history)))
        assertEquals(success(f.company()), success(f.company(history = history)))
        assertEquals("Changed text", history.latestEdit?.edit?.text)
        val target =
            CollaborationEditTarget(
                f.device.roomId,
                "\$target",
                f.otherId,
                f.origin.sender,
                "d".repeat(64),
                500,
                500,
            )
        val groupEdit = f.group.copy(intent = f.group.intent.copy(editTarget = target))
        val companyEdit = f.company.copy(intent = f.company.intent.copy(editTarget = target))
        assertTrue(validCollaborationSpaceMessageRecord(f.access, groupEdit))
        assertTrue(validCollaborationEditRecord(companyEdit))
        assertNull(success(f.group(groupEdit)))
        assertNull(success(f.company(companyEdit)))
    }

    @Test
    fun malformedHistoryAndCorruptRecordsFailInsteadOfBecomingUnmatched() {
        val f = Fixture()
        for (history in
            listOf(
                f.history.copy(eventIds = emptyList()),
                f.history.copy(contentHash = "invalid"),
                f.history.copy(timestamp = -1),
            )) {
            assertIs<AppResult.Failed>(f.group(history = history))
            assertIs<AppResult.Failed>(f.company(history = history))
        }
        assertIs<AppResult.Failed>(f.group(f.group.copy(revision = -1)))
        assertIs<AppResult.Failed>(f.company(f.company.copy(revision = -1)))
    }

    @Test
    fun nativeGroupRoomsAcceptGroupOriginalsWithoutInventingACompanyOrigin() {
        val f = Fixture(false)
        assertNotNull(success(f.group()))
        assertIs<AppResult.Failed>(f.company())
    }

    @Test
    fun bindingAdmissionAndRedactedDiagnosticsDoNotLeakMessageContent() {
        val f = Fixture()
        val foreign =
            f.binding.copy(conversation = f.binding.conversation.copy(spaceId = f.otherId))
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxOriginalAssociation(f.access, foreign, f.group, f.history)
        )
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxOriginalAssociation(f.access, foreign, f.company, f.history)
        )
        val text = assertNotNull(success(f.group())).toString()
        assertFalse(text.contains(f.id))
        assertFalse(text.contains(f.history.text!!))
        assertTrue(text.contains("[redacted]"))
    }

    private fun <T> success(value: AppResult<T>) = assertIs<AppResult.Success<T>>(value).value

    private class Fixture(legacy: Boolean = true) {
        val room = SpaceRoomBindingPreparationFixture(legacy)
        val access = room.access
        val binding = room.binding
        val id = "ce000000-0000-4000-8000-000000000001"
        val otherId = "ce000000-0000-4000-8000-000000000002"
        val time = Instant.fromEpochMilliseconds(1000)
        val origin = CollaborationMessageOrigin("@author:example.test", "a".repeat(64))
        val device =
            CollaborationMessageDevice(
                id,
                otherId,
                "ce000000-0000-4000-8000-000000000003",
                room.roomId,
            )
        val state = CollaborationMessageState.Staged(device, "txn", origin)
        val group =
            CollaborationSpaceMessageRecord(
                CollaborationSpaceMessageIntent(
                    id,
                    access.messageOwner(),
                    binding.conversation.id,
                    time,
                    "Original text",
                ),
                2,
                state,
            )
        val companyAccess =
            CollaborationLocalAccess(
                access.sessionId,
                access.audience,
                access.accountId,
                room.position.companyId,
            )
        val company =
            CollaborationMessageRecord(
                CollaborationMessageIntent(
                    id,
                    companyAccess.messageOwner(),
                    room.position.conversationId,
                    time,
                    "Original text",
                ),
                2,
                state,
            )
        val history =
            CollaborationHistoryMessage(
                origin.sender,
                id,
                "\$event",
                2000,
                origin.contentHash,
                "Original text",
                listOf("\$event"),
                false,
            )

        fun group(
            record: CollaborationSpaceMessageRecord = group,
            history: CollaborationHistoryMessage = this.history,
        ) = collaborationSpaceInboxOriginalAssociation(access, binding, record, history)

        fun company(
            record: CollaborationMessageRecord = company,
            history: CollaborationHistoryMessage = this.history,
        ) = collaborationSpaceInboxOriginalAssociation(access, binding, record, history)
    }
}
