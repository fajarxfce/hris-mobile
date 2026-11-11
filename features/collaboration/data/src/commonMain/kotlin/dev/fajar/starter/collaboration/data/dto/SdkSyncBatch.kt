package dev.fajar.starter.collaboration.data.dto

/** One processed SDK response. Its positions must never be replaced with a later store read. */
internal data class SdkSyncBatch(
    val previousBatch: String?,
    val nextBatch: String,
    val rooms: List<SdkSyncRoomChange>,
    val rescanRequired: Boolean,
) {
    override fun toString() = "SdkSyncBatch([redacted])"
}

internal data class SdkSyncRoomChange(
    val roomId: String,
    val membership: SdkSyncMembership,
    val eventIds: List<String>,
    val limited: Boolean,
) {
    override fun toString() = "SdkSyncRoomChange([redacted])"
}

internal enum class SdkSyncMembership {
    Joined,
    Left,
}

internal sealed interface SdkSyncOutcome {
    data class Received(val batch: SdkSyncBatch) : SdkSyncOutcome

    data class Failed(val problem: SdkSyncProblem) : SdkSyncOutcome
}
