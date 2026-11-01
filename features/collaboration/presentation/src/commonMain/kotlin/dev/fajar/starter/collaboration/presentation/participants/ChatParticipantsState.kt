package dev.fajar.starter.collaboration.presentation.participants

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.Failure

data class ChatParticipantsState(
    val query: String = "",
    val page: CollaborationPage<CollaborationParticipant>? = null,
    val after: String? = null,
    val previous: List<String?> = emptyList(),
    val loading: Boolean = true,
    val localFailure: Failure? = null,
    val failure: Failure? = null,
    val selected: CollaborationParticipant? = null,
    val opening: Boolean = false,
    val openFailure: Failure? = null,
    val openedConversationId: String? = null,
)
