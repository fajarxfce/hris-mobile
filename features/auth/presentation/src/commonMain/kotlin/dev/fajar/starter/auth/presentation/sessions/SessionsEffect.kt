package dev.fajar.starter.auth.presentation.sessions

sealed interface SessionsEffect {
    data object Back : SessionsEffect
}
