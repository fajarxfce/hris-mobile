package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.matchCollaborationOriginalQueue

internal class OriginalQueueMatchFixture(
    val native: CollaborationSpaceFixture = CollaborationSpaceFixture()
) {
    val owner = native.owner
    val position = CollaborationOriginalConversationPosition(id(41), id(51))
    val runtime = CollaborationSpaceRuntimeReady(owner, id(61), id(71))
    val binding =
        CollaborationSetupBinding(
            owner.parent.audience,
            owner.parent.accountId,
            position.companyId,
            id(81),
        )
    val device =
        CollaborationMessageDevice(
            binding.nativeSessionId,
            id(62),
            id(91),
            "!original:example.test",
        )
    val peer = id(2)
    private val pair = listOf(owner.parent.accountId, peer).sorted()
    val conversation =
        CollaborationSpaceOriginalConversation(
            owner,
            CollaborationOriginalConversation(
                position,
                pair[0],
                pair[1],
                owner.parent.accountId,
                native.setup.now,
                0,
            ),
            CollaborationOriginalRoom("example.test", device.roomId, native.setup.now, peer),
        )
    val identity =
        CollaborationSpaceIdentity(
            owner.spaceId,
            owner.parent.accountId,
            "example.test",
            "@hris_${position.companyId.replace("-", "")}_${owner.parent.accountId.replace("-", "")}:example.test",
            CollaborationSpaceIdentityOrigin.LEGACY,
            position.companyId,
            0,
            native.setup.now,
        )
    val origin = CollaborationMessageOrigin(identity.matrixUserId, "a".repeat(64))
    val original =
        CollaborationQueuedOriginal(
            "original-transaction",
            id(101),
            origin,
            "m.room.message",
            "{\"msgtype\":\"m.text\",\"body\":\"Fictional original\"}",
        )
    val record =
        CollaborationMessageRecord(
            CollaborationMessageIntent(
                requireNotNull(original.logicalId),
                CollaborationMessageOwner(binding.audience, binding.accountId, binding.companyId),
                position.conversationId,
                native.setup.now,
                "Fictional original",
            ),
            2,
            CollaborationMessageState.Staged(device, original.transactionId, origin),
        )
    val snapshot =
        CollaborationSpaceOriginalQueueSnapshot(
            runtime,
            CollaborationOriginalQueueSnapshot(binding, device, listOf(original)),
        )

    fun match(
        expected: CollaborationSpaceRuntimeReady = runtime,
        metadata: CollaborationSpaceOriginalConversation = conversation,
        selected: CollaborationSpaceIdentity = identity,
        captured: CollaborationSpaceOriginalQueueSnapshot = snapshot,
        records: List<CollaborationMessageRecord> = listOf(record),
        completions: List<CollaborationMessageCompletion> = emptyList(),
    ) =
        matchCollaborationOriginalQueue(
            expected,
            metadata,
            selected,
            captured,
            records,
            completions,
        )

    fun sent(value: CollaborationMessageRecord = record, event: String = "\$accepted") =
        value.copy(
            revision = 3,
            state =
                CollaborationMessageState.Sent(
                    (value.state as CollaborationMessageState.Staged).device,
                    value.state.transactionId,
                    event,
                    value.state.origin,
                ),
        )

    fun id(value: Int) = "00000000-0000-4000-8000-" + value.toString().padStart(12, '0')
}
