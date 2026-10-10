package dev.fajar.starter.auth.presentation.verification

sealed interface VerificationEffect {
    data object Verified : VerificationEffect
}
