package dev.fajar.starter.security.presentation.lock
sealed interface AppLockEvent {
    data object Foregrounded : AppLockEvent

    data object Backgrounded : AppLockEvent

    data object UnlockRequested : AppLockEvent

    data object AccountSignInRequested : AppLockEvent

    data object InteractionReceived : AppLockEvent
}
