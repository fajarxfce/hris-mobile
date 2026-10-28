package dev.fajar.starter.identity.domain.firebase.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.firebase.entities.*

interface FirebaseAuthenticationRepository {
    /** Installed provider capabilities; must not open an interactive window. */
    suspend fun configuration(): AppResult<FirebaseSignInConfiguration>

    /** Own provider resources until completion/cancellation; do not retain a Firebase session. */
    suspend fun authenticate(input: FirebaseSignInInput): AppResult<FirebaseSignInProof>
}
