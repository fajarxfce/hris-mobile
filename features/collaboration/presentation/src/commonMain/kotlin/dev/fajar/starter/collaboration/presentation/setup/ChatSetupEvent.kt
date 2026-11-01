package dev.fajar.starter.collaboration.presentation.setup

sealed interface ChatSetupEvent {
    data object Entered : ChatSetupEvent

    data object Stopped : ChatSetupEvent

    data object BackRequested : ChatSetupEvent

    data object RefreshRequested : ChatSetupEvent

    data object ContinueRequested : ChatSetupEvent

    data object OpenChatRequested : ChatSetupEvent
}
