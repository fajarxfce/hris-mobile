package dev.fajar.starter.collaboration.data.dto

internal sealed interface CollaborationHistoryRequest {
    val roomId: String

    data class Head(override val roomId: String) : CollaborationHistoryRequest {
        override fun toString() = "HistoryHeadRequest([redacted])"
    }

    data class Page(override val roomId: String, val position: String?) :
        CollaborationHistoryRequest {
        override fun toString() = "HistoryPageRequest([redacted])"
    }
}
