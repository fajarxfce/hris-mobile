package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.CollaborationDirectSummary
import dev.fajar.starter.common.result.Failure

data class ChatForwardState(
    val messageCount: Int,
    val targetLimit: Int,
    val targets: List<CollaborationDirectSummary> = emptyList(),
    val selectedIds: List<String> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val busy: Boolean = false,
    val locked: Boolean = false,
    val failure: Failure? = null,
) {
    val canConfirm: Boolean
        get() = !loading && !busy && selectedIds.isNotEmpty()

    override fun toString() = "ChatForwardState([redacted])"
}
