package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.toCollaborationTimelineItem
import dev.fajar.starter.collaboration.data.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.matrix.rustcomponents.sdk.*

/** Owns partial acquisition too. The parent always calls stop, including when open fails. */
internal class AndroidCollaborationTimelineSource(
    private val client: Client,
    private val roomId: String,
) {
    private var room: Room? = null
    private var timeline: Timeline? = null
    private var listener: TaskHandle? = null
    private val projectionLock = Any()
    private var closed = false
    private var failed = false
    private var items = emptyList<CollaborationSdkTimelineItemDto?>()
    private val mutableUpdates = MutableStateFlow(CollaborationSdkTimelineDto(emptyList()))
    val updates = mutableUpdates.asStateFlow()

    suspend fun open() {
        // Local lookup: joining an invitation and live admission belong to separate operations.
        val acquiredRoom = requireNotNull(client.getRoom(roomId))
        room = acquiredRoom
        require(acquiredRoom.isEncrypted())
        val acquiredTimeline = acquiredRoom.timeline()
        timeline = acquiredTimeline
        // The initial RESET arrives synchronously before addListener returns its handle.
        listener =
            acquiredTimeline.addListener(
                object : TimelineListener {
                    override fun onUpdate(diff: List<TimelineDiff>) = receive(diff)
                }
            )
        synchronized(projectionLock) { check(!failed) }
    }

    private fun receive(changes: List<TimelineDiff>) {
        try {
            synchronized(projectionLock) {
                if (closed || failed) return
                try {
                    require(changes.size <= 4096)
                    require(
                        changes.sumOf {
                            when (it) {
                                is TimelineDiff.Reset -> it.values.size.toLong()
                                is TimelineDiff.Append -> it.values.size.toLong()
                                else -> 1L
                            }
                        } <= 4096
                    )
                    val next = reduceSdkTimeline(items, changes.map { it.copyDelta() })
                    val messages = next.filterNotNull()
                    require(messages.sumOf { it.body?.length ?: 0 } <= 1_048_576)
                    require(messages.map { it.key }.distinct().size == messages.size)
                    items = next
                    mutableUpdates.value = CollaborationSdkTimelineDto(messages)
                } catch (_: Exception) {
                    // Never let malformed data unwind through the FFI callback. No skipped deltas.
                    failed = true
                    mutableUpdates.value =
                        CollaborationSdkTimelineDto(emptyList(), SdkTimelineStatus.INVALID)
                }
            }
        } finally {
            // Dispose every native value even if a preceding disposal fails.
            for (change in changes) try {
                change.destroy()
            } catch (_: Exception) {
                synchronized(projectionLock) {
                    failed = true
                    if (!closed)
                        mutableUpdates.value =
                            CollaborationSdkTimelineDto(emptyList(), SdkTimelineStatus.INVALID)
                }
            }
        }
    }

    suspend fun stop() {
        synchronized(projectionLock) {
            closed = true
            items = emptyList()
            mutableUpdates.value =
                CollaborationSdkTimelineDto(emptyList(), SdkTimelineStatus.CLOSED)
        }
        val task = listener
        if (task != null) {
            task.cancel()
            checkNotNull(
                withTimeoutOrNull(5_000) {
                    while (!task.isFinished()) delay(10)
                    Unit
                }
            ) {
                "Native timeline is still stopping."
            }
            task.close()
            listener = null
        }
        timeline?.close()
        timeline = null
        room?.close()
        room = null
    }
}

private fun TimelineDiff.copyDelta(): SdkTimelineDelta<CollaborationSdkTimelineItemDto?> =
    when (this) {
        is TimelineDiff.Reset -> {
            require(values.size <= 512)
            SdkTimelineDelta.Reset(values.map { it.toCollaborationTimelineItem() })
        }
        is TimelineDiff.Append -> {
            require(values.size <= 512)
            SdkTimelineDelta.Append(values.map { it.toCollaborationTimelineItem() })
        }
        is TimelineDiff.Insert ->
            SdkTimelineDelta.Insert(index, value.toCollaborationTimelineItem())
        is TimelineDiff.Set -> SdkTimelineDelta.Set(index, value.toCollaborationTimelineItem())
        is TimelineDiff.PushFront -> SdkTimelineDelta.PushFront(value.toCollaborationTimelineItem())
        is TimelineDiff.PushBack -> SdkTimelineDelta.PushBack(value.toCollaborationTimelineItem())
        is TimelineDiff.Remove -> SdkTimelineDelta.Remove(index)
        is TimelineDiff.Truncate -> SdkTimelineDelta.Truncate(length)
        is TimelineDiff.Clear -> SdkTimelineDelta.Clear
        is TimelineDiff.PopFront -> SdkTimelineDelta.PopFront
        is TimelineDiff.PopBack -> SdkTimelineDelta.PopBack
    }
