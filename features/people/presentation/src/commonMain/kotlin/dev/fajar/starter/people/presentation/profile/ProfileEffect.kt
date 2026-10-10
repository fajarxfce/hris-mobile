package dev.fajar.starter.people.presentation.profile

sealed interface ProfileEffect {
    data object Back : ProfileEffect

    data object VerifyAccount : ProfileEffect
}
