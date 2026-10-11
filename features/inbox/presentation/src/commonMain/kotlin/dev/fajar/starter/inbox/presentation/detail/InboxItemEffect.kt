package dev.fajar.starter.inbox.presentation.detail

sealed interface InboxItemEffect {
    data object Back : InboxItemEffect

    data object VerifyAccount : InboxItemEffect
}
