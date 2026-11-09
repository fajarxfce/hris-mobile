package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*

class CollaborationOriginalTransportTest {
    @Test
    fun matchesDistinctLogicalAndOriginalPhysicalIdsWithoutRequiringRecipientOnline() {
        val f = OriginalTransportFixture()
        assertNotEquals(f.queue.runtime.deviceId, f.queue.device.deviceId)
        assertNotEquals(f.queue.owner.parent.nativeSessionId, f.queue.binding.nativeSessionId)
        assertFalse(f.transport.recipientReady)
        assertEquals(
            f.queue.conversation,
            assertIs<AppResult.Success<CollaborationSpaceOriginalConversation>>(f.match()).value,
        )
        assertEquals(
            f.queue.conversation,
            assertIs<AppResult.Success<CollaborationSpaceOriginalConversation>>(
                    f.match(f.transport.copy(recipientReady = true))
                )
                .value,
        )
    }

    @Test
    fun logicalOwnerAndOriginalPhysicalDeviceCannotBeSubstituted() {
        val f = OriginalTransportFixture()
        val bad =
            listOf(
                f.transport.copy(device = f.device.copy(id = f.queue.id(999))),
                f.transport.copy(
                    matrixDeviceId = "HRIS_${f.queue.runtime.deviceId.replace("-", "")}"
                ),
                f.transport.copy(
                    device = f.device.copy(origin = CollaborationSpaceDeviceOrigin.SPACE),
                    matrixDeviceId = "HRISG_${f.queue.runtime.deviceId.replace("-", "")}",
                ),
                f.transport.copy(device = f.device.copy(platform = CollaborationNativePlatform.IOS)),
            )
        for (value in bad) assertIs<AppResult.Failed>(f.match(value))
        assertIs<AppResult.Failed>(
            f.match(
                captured =
                    f.queue.snapshot.copy(
                        runtime = f.queue.runtime.copy(operationId = f.queue.id(999))
                    )
            )
        )
        assertIs<AppResult.Failed>(
            f.match(
                captured =
                    f.queue.snapshot.copy(
                        original =
                            f.queue.snapshot.original.copy(
                                binding = f.queue.binding.copy(companyId = f.queue.id(999))
                            )
                    )
            )
        )
    }

    @Test
    fun changedCanonicalOrPublishedRoomNeverBorrowsOriginalQueueAuthority() {
        val f = OriginalTransportFixture()
        assertIs<AppResult.Failed>(
            f.match(f.transport.copy(conversation = f.conversation.copy(id = f.queue.id(999))))
        )
        assertIs<AppResult.Failed>(f.match(retained = f.binding.copy(original = null)))
        assertIs<AppResult.Failed>(
            f.match(
                retained =
                    f.binding.copy(conversation = f.binding.conversation.copy(id = f.queue.id(999)))
            )
        )
        assertIs<AppResult.Failed>(
            f.match(f.transport.copy(room = f.room.copy(roomId = "!another:example.test")))
        )
        val wrongSender =
            f.queue.original.copy(origin = f.queue.origin.copy(sender = "@foreign:example.test"))
        assertIs<AppResult.Failed>(
            f.match(
                captured =
                    f.queue.snapshot.copy(
                        original = f.queue.snapshot.original.copy(messages = listOf(wrongSender))
                    )
            )
        )
    }
}
