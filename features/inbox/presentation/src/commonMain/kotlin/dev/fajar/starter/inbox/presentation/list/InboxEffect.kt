package dev.fajar.starter.inbox.presentation.list

sealed interface InboxEffect {
    data object Back : InboxEffect

    data object ScrollToStart : InboxEffect

    data object VerifyAccount : InboxEffect

    data class OpenItem(val id: String) : InboxEffect
}
