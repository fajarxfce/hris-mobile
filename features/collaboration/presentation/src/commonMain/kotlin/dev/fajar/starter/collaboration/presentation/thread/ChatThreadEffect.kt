package dev.fajar.starter.collaboration.presentation.thread

sealed interface ChatThreadEffect {
    data object Back : ChatThreadEffect

    data object Setup : ChatThreadEffect

    data object ScrollToNewest : ChatThreadEffect
}
