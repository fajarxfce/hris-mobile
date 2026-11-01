package dev.fajar.starter.collaboration.presentation.participants

sealed interface ChatParticipantsEvent {
    data object Entered : ChatParticipantsEvent

    data object Stopped : ChatParticipantsEvent

    data object BackRequested : ChatParticipantsEvent

    data class QueryChanged(val value: String) : ChatParticipantsEvent

    data object RetryRequested : ChatParticipantsEvent

    data object NextRequested : ChatParticipantsEvent

    data object PreviousRequested : ChatParticipantsEvent

    data class ParticipantSelected(val accountId: String) : ChatParticipantsEvent

    data object RetryOpenRequested : ChatParticipantsEvent

    data object CancelSelectionRequested : ChatParticipantsEvent
}
