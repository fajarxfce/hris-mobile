package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryChange
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationReplacementSourceTest {
    @Test
    fun originalAndReplacementIdentitiesAreDetachedSeparatelyBeforeNativeDisposal() = runTest {
        val room = HistoryRoom()
        room.item.replacementJson = replacement()
        val children = SdkChildResources(this)
        val windows = windows(room, children, StandardTestDispatcher(testScheduler))
        try {
            val result =
                assertIs<AppResult.Success<AndroidCollaborationHistoryWindow>>(
                        windows.read("!room:test", "\$original")
                    )
                    .value
            val original = assertIs<CollaborationHistoryChange.Text>(result.originals.single())
            val edit = result.edits.single()
            assertEquals("Original text", original.text)
            assertEquals("\$original", original.eventId)
            assertEquals("\$revision", edit.eventId)
            assertEquals("\$original", edit.targetEventId)
            assertEquals("Revised native text", edit.text)
            assertEquals(2L, edit.timestamp)
            assertEquals(room.item.replacementJson!!.length.toLong(), result.replacementCharacters)
            assertEquals(1, room.item.copied)
            assertEquals(1, room.item.disposed)
            assertEquals("", room.item.lastDebugInfo?.model)
            assertNull(room.item.lastDebugInfo?.originalJson)
            assertNull(room.item.lastDebugInfo?.latestEditJson)
            assertEquals(1, room.calls.count { it == "room.close" })
        } finally {
            assertEquals(AppResult.Success(Unit), children.close())
        }
    }

    @Test
    fun malformedOrForeignReplacementNeverSuppressesOrRewritesTheValidOriginal() = runTest {
        for (json in
            listOf(
                "invalid json",
                replacement(target = "\$foreign"),
                replacement(sender = "@other:example.test"),
            )) {
            val room = HistoryRoom()
            room.item.replacementJson = json
            val children = SdkChildResources(this)
            try {
                val result =
                    assertIs<AppResult.Success<AndroidCollaborationHistoryWindow>>(
                            windows(room, children, StandardTestDispatcher(testScheduler))
                                .read("!room:test", "\$original")
                        )
                        .value
                assertEquals(
                    "Original text",
                    assertIs<CollaborationHistoryChange.Text>(result.originals.single()).text,
                )
                assertTrue(result.edits.isEmpty())
                assertEquals(1, room.item.disposed)
                assertNull(room.item.lastDebugInfo?.latestEditJson)
            } finally {
                assertEquals(AppResult.Success(Unit), children.close())
            }
        }
    }

    @Test
    fun oversizedReplacementReleasesEveryOwnedNativeValueWithoutReturningPartialData() = runTest {
        val room = HistoryRoom()
        room.item.replacementJson = "x".repeat(96_001)
        val children = SdkChildResources(this)
        try {
            assertIs<AppResult.Failed>(
                windows(room, children, StandardTestDispatcher(testScheduler))
                    .read("!room:test", "\$original")
            )
            assertEquals(1, room.item.disposed)
            assertEquals(1, room.calls.count { it == "room.close" })
            assertEquals("", room.item.lastDebugInfo?.model)
            assertNull(room.item.lastDebugInfo?.latestEditJson)
        } finally {
            assertEquals(AppResult.Success(Unit), children.close())
        }
    }

    private fun windows(
        room: HistoryRoom,
        children: SdkChildResources,
        dispatcher: CoroutineDispatcher,
    ) =
        AndroidCollaborationHistoryWindows(
            object : Client(NoHandle) {
                override fun getRoom(roomId: String): Room = room
            },
            children,
            dispatcher,
        ) {
            null
        }

    private fun replacement(
        target: String = "\$original",
        sender: String = "@fictional:example.test",
    ) =
        buildJsonObject {
                put("type", "m.room.message")
                put("event_id", "\$revision")
                put("sender", sender)
                put("origin_server_ts", 2)
                putJsonObject("content") {
                    put("msgtype", "m.text")
                    put("body", "* Revised native text")
                    putJsonObject("m.relates_to") {
                        put("rel_type", "m.replace")
                        put("event_id", target)
                    }
                    putJsonObject("m.new_content") {
                        put("msgtype", "m.text")
                        put("body", "Revised native text")
                        put("org.hris.message_id", "a1000000-0000-4000-8000-000000000001")
                    }
                }
            }
            .toString()
}
