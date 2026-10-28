package dev.fajar.starter.identity.domain.firebase.entities

enum class FirebaseSignInProvider(val id: String) {
    EmailPassword("password"),
    Google("google.com"),
}

data class FirebaseSignInConfiguration(
    val enabled: Boolean,
    val projectId: String?,
    val providers: Set<FirebaseSignInProvider>,
)

sealed interface FirebaseSignInInput {
    val provider: FirebaseSignInProvider

    data class EmailPassword(val email: String, val password: String) : FirebaseSignInInput {
        override val provider = FirebaseSignInProvider.EmailPassword

        override fun toString() = "FirebaseSignInInput.EmailPassword(<redacted>)"
    }

    data object Google : FirebaseSignInInput {
        override val provider = FirebaseSignInProvider.Google
    }
}

/** Opaque provider proof. Only the HRIS server verifies its signature and resolves an account. */
data class FirebaseSignInProof(
    val projectId: String,
    val provider: FirebaseSignInProvider,
    val idToken: String,
) {
    override fun toString() = "FirebaseSignInProof(<redacted>)"
}
