package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationMessageQueueSource
import dev.fajar.starter.collaboration.data.messages.collaborationOutgoingContent
import dev.fajar.starter.collaboration.domain.entities.CollaborationEditTarget
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.*

class AndroidCollaborationEditQueueSourceTest {
    private val operation = "00000000-0000-0000-0000-000000000091"
    private val target =
        CollaborationEditTarget(
            "!room:test",
            "\$original",
            "00000000-0000-0000-0000-000000000092",
            "@self:test",
            "a".repeat(64),
            1_000,
            1_000,
        )

    @Test
    fun typedSdkContentMatchesTheBoundedJsonFallbackAndClosesBothHandles() = runTest {
        val room = QueueRoom()
        var typedBody: String? = null
        val source =
            AndroidCollaborationMessageQueueSource(
                createTextContent = { body ->
                    typedBody = body
                    object : RoomMessageEventContentWithoutRelation(NoHandle) {
                        override fun destroy() {
                            room.calls += "content.close"
                        }
                    }
                }
            ) {
                room
            }
        source.open()
        val text = "界".repeat(500)
        source.enqueue(operation, text, null, false, target)
        val content = Json.parseToJsonElement(assertNotNull(room.timeline.sentJson)).jsonObject
        assertEquals(collaborationOutgoingContent(operation, text, null, false, target), content)
        assertEquals(content.getValue("body").jsonPrimitive.content, typedBody)
        assertEquals(
            text,
            content.getValue("m.new_content").jsonObject.getValue("body").jsonPrimitive.content,
        )
        assertNotEquals(text, typedBody)
        assertFalse(room.enabled)
        assertEquals(1, room.calls.count { it == "content.close" })
        assertEquals(1, room.calls.count { it == "send.close" })
        source.stop()
        assertEquals(1, room.calls.count { it == "timeline.close" })
    }

    @Test
    fun cancellationClosesCreatedContentWithoutInventingASendHandleOrEnablingDelivery() = runTest {
        val room = QueueRoom()
        val entered = CompletableDeferred<Unit>()
        room.timeline.beforeEnqueue = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val source =
            AndroidCollaborationMessageQueueSource(
                createTextContent = {
                    object : RoomMessageEventContentWithoutRelation(NoHandle) {
                        override fun destroy() {
                            room.calls += "content.close"
                        }
                    }
                }
            ) {
                room
            }
        source.open()
        val pending = async { source.enqueue(operation, "Edit", null, false, target) }
        entered.await()
        pending.cancelAndJoin()
        assertEquals(1, room.calls.count { it == "content.close" })
        assertEquals(0, room.calls.count { it == "send.close" })
        assertFalse(room.enabled)
        source.stop()
        assertEquals(1, room.calls.count { it == "room.close" })
    }
}
