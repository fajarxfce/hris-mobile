package dev.fajar.starter.collaboration.data.dto

internal enum class SdkTimelineStatus {
    READY,
    INVALID,
    CLOSED,
}

internal enum class SdkMessageKind {
    TEXT,
    REDACTED,
    UNABLE_TO_DECRYPT,
    UNSUPPORTED,
}

internal enum class SdkMessageDelivery {
    PENDING,
    FAILED,
    SENT,
}

internal data class CollaborationSdkTimelineDto(
    val items: List<CollaborationSdkTimelineItemDto>,
    val status: SdkTimelineStatus = SdkTimelineStatus.READY,
) {
    override fun toString() = "CollaborationSdkTimelineDto([redacted])"
}

internal data class CollaborationSdkTimelineItemDto(
    val key: String,
    val eventId: String?,
    val transactionId: String?,
    val logicalId: String?,
    val sender: String,
    val timestamp: Long,
    val own: Boolean,
    val kind: SdkMessageKind,
    val body: String?,
    val edited: Boolean,
    val delivery: SdkMessageDelivery,
) {
    override fun toString() = "CollaborationSdkTimelineItemDto([redacted])"
}
