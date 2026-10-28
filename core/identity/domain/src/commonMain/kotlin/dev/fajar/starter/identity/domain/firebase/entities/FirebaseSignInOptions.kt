package dev.fajar.starter.identity.domain.firebase.entities

data class FirebaseSignInOptions(
    val firebaseEmail: Boolean = false,
    val google: Boolean = false,
    val emailAvailable: Boolean = true,
)
