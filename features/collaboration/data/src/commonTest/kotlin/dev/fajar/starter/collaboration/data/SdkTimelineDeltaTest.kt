package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.sdk.*
import kotlin.test.*

class SdkTimelineDeltaTest {
    @Test
    fun virtualEntriesKeepTheirPositionsWhileLocalEchoIsReplacedAndHistoryIsPrepended() {
        val original = listOf(null, "local", "later")
        val actual =
            reduceSdkTimeline(
                original,
                listOf(
                    SdkTimelineDelta.Set(1u, "remote"),
                    SdkTimelineDelta.PushFront("older"),
                    SdkTimelineDelta.Insert(2u, "middle"),
                    SdkTimelineDelta.Append(listOf(null, "new")),
                    SdkTimelineDelta.Remove(4u),
                ),
            )
        assertEquals(listOf("older", null, "middle", "remote", null, "new"), actual)
        assertEquals(listOf(null, "local", "later"), original)
    }

    @Test
    fun resetClearPopAndTruncateFollowSdkVectorSemantics() {
        val actual =
            reduceSdkTimeline(
                listOf("discard"),
                listOf(
                    SdkTimelineDelta.Reset(listOf("a", "b", "c")),
                    SdkTimelineDelta.PopBack,
                    SdkTimelineDelta.PushBack("d"),
                    SdkTimelineDelta.PopFront,
                    SdkTimelineDelta.Truncate(UInt.MAX_VALUE),
                    SdkTimelineDelta.Truncate(1u),
                ),
            )
        assertEquals(listOf("b"), actual)
        assertEquals(emptyList(), reduceSdkTimeline(actual, listOf(SdkTimelineDelta.Clear)))
    }

    @Test
    fun malformedLateDeltaNeverPublishesTheEarlierChangesInItsBatch() {
        val original = listOf("a", "b")
        assertFailsWith<IllegalArgumentException> {
            reduceSdkTimeline(
                original,
                listOf(SdkTimelineDelta.Set(0u, "changed"), SdkTimelineDelta.Remove(UInt.MAX_VALUE)),
            )
        }
        assertEquals(listOf("a", "b"), original)
        assertFailsWith<IllegalArgumentException> {
            reduceSdkTimeline(emptyList<String>(), listOf(SdkTimelineDelta.PopBack))
        }
        assertFailsWith<IllegalArgumentException> {
            reduceSdkTimeline(original, listOf(SdkTimelineDelta.Insert(3u, "x")))
        }
    }

    @Test
    fun boundsAreCheckedBeforeInsertionAndCannotBeBypassedByALaterTruncate() {
        val original = List(512) { it }
        assertFailsWith<IllegalArgumentException> {
            reduceSdkTimeline(
                original,
                listOf(SdkTimelineDelta.PushBack(513), SdkTimelineDelta.Truncate(1u)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            reduceSdkTimeline(emptyList<Int>(), listOf(SdkTimelineDelta.Reset(List(513) { it })))
        }
        assertFailsWith<IllegalArgumentException> {
            reduceSdkTimeline(original, List(4097) { SdkTimelineDelta.Clear })
        }
        assertEquals(512, original.size)
    }
}
