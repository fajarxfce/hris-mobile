package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*
import kotlinx.coroutines.*
import org.matrix.rustcomponents.sdk.*
import uniffi.matrix_sdk_ui.TimelineEventFocusThreadMode
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

/** One event-focused original snapshot. The parent owns partial acquisition and disposal. */
internal class AndroidCollaborationHistorySource(
    private val anchor: String,
    private val acquireRoom: suspend () -> Room,
) {
    private var room: Room? = null
    private var timeline: Timeline? = null
    private var listener: TaskHandle? = null
    private val lock = Any()
    private var closed = false
    private var captured = false
    private var failed = false
    private var events = emptyList<CollaborationSdkHistoryEventDto>()
    private var replacements = emptyList<CollaborationSdkReplacementDto>()
    private var replacementCharacters = 0L
    private var extractionNanos = 0L
    private var modelCharacters = 0L
    private var originalCharacters = 0L

    suspend fun open() {
        val acquiredRoom = acquireRoom()
        room = acquiredRoom
        require(acquiredRoom.isEncrypted())
        val acquiredTimeline =
            acquiredRoom.timelineWithConfiguration(
                TimelineConfiguration(
                    focus =
                        TimelineFocus.Event(
                            anchor,
                            20u,
                            TimelineEventFocusThreadMode.Automatic(false),
                        ),
                    filter = TimelineFilter.All,
                    internalIdPrefix = null,
                    dateDividerMode = DateDividerMode.DAILY,
                    trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
                    reportUtds = false,
                )
            )
        timeline = acquiredTimeline
        listener =
            acquiredTimeline.addListener(
                object : TimelineListener {
                    override fun onUpdate(diff: List<TimelineDiff>) = receive(diff)
                }
            )
        synchronized(lock) { check(captured && !failed && !closed) }
    }

    fun snapshot() =
        synchronized(lock) {
            check(captured && !failed && !closed)
            require(events.any { it.eventId == anchor })
            AndroidHistoryRawSnapshot(
                events.toList(),
                extractionNanos,
                modelCharacters,
                originalCharacters,
                replacements.toList(),
                replacementCharacters,
            )
        }

    private fun receive(changes: List<TimelineDiff>) {
        try {
            synchronized(lock) {
                if (closed || captured || failed) return
                val started = System.nanoTime()
                try {
                    // Pinned SDK addListener guarantees exactly one initial Reset, synchronously.
                    val initial = changes.single() as TimelineDiff.Reset
                    require(initial.values.size <= 128)
                    val copiedReplacements = mutableListOf<CollaborationSdkReplacementDto>()
                    val copied = initial.values.mapNotNull { copyEvent(it, copiedReplacements) }
                    require(copied.map { it.eventId }.distinct().size == copied.size)
                    replacements = copiedReplacements.toList()
                    events = copied
                    captured = true
                } catch (_: Exception) {
                    failed = true
                    events = emptyList()
                    replacements = emptyList()
                } finally {
                    extractionNanos = System.nanoTime() - started
                }
            }
        } finally {
            for (change in changes) try {
                change.destroy()
            } catch (_: Exception) {
                synchronized(lock) {
                    failed = true
                    events = emptyList()
                    replacements = emptyList()
                }
            }
        }
    }

    private fun copyEvent(
        item: TimelineItem,
        edits: MutableList<CollaborationSdkReplacementDto>,
    ): CollaborationSdkHistoryEventDto? {
        val event = item.asEvent() ?: return null
        try {
            val id =
                (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId
                    ?: return null
            require(event.timestamp <= 9_007_199_254_740_991uL)
            val content = (event.content as? TimelineItemContent.MsgLike)?.content
            val kind =
                when (val value = content?.kind) {
                    is MsgLikeKind.Redacted -> SdkMessageKind.REDACTED
                    is MsgLikeKind.UnableToDecrypt -> SdkMessageKind.UNABLE_TO_DECRYPT
                    is MsgLikeKind.Message ->
                        if (value.content.msgType is MessageType.Text) SdkMessageKind.TEXT
                        else SdkMessageKind.UNSUPPORTED
                    else -> SdkMessageKind.UNSUPPORTED
                }
            val json =
                if (kind == SdkMessageKind.TEXT) {
                    // This pinned binding has no standalone originalJson getter. Copy once, discard
                    // its expensive diagnostic model immediately and expose only numeric
                    // measurements.
                    val info = event.lazyProvider.debugInfo()
                    try {
                        modelCharacters += info.model.length
                        originalCharacters += info.originalJson?.length ?: 0
                        info.latestEditJson?.let { json ->
                            require(json.length <= 96_000)
                            replacementCharacters += json.length
                            edits += CollaborationSdkReplacementDto(id, event.sender, json)
                        }
                        require(originalCharacters + replacementCharacters <= 4_194_304L)
                        require(modelCharacters <= 4_194_304L && originalCharacters <= 4_194_304L)
                        info.originalJson?.also { require(it.length <= 96_000) }
                    } finally {
                        info.model = ""
                        info.originalJson = null
                        info.latestEditJson = null
                    }
                } else null
            return CollaborationSdkHistoryEventDto(
                id,
                event.sender,
                event.timestamp.toLong(),
                kind,
                json,
            )
        } finally {
            event.destroy()
        }
    }

    suspend fun stop() {
        synchronized(lock) {
            closed = true
            events = emptyList()
            replacements = emptyList()
        }
        listener?.let { task ->
            task.cancel()
            checkNotNull(
                withTimeoutOrNull(5_000) {
                    while (!task.isFinished()) delay(10)
                    Unit
                }
            )
            task.close()
            listener = null
        }
        timeline?.close()
        timeline = null
        room?.close()
        room = null
    }
}

internal data class AndroidHistoryRawSnapshot(
    val events: List<CollaborationSdkHistoryEventDto>,
    val extractionNanos: Long,
    val modelCharacters: Long,
    val originalCharacters: Long,
    val replacements: List<CollaborationSdkReplacementDto> = emptyList(),
    val replacementCharacters: Long = 0L,
) {
    override fun toString() = "AndroidHistoryRawSnapshot([redacted])"
}
