package dev.fajar.starter.collaboration.presentation.setup

sealed interface ChatSetupEffect {
    data object Back : ChatSetupEffect

    data class Ready(val deviceId: String) : ChatSetupEffect
}
