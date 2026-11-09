package dev.fajar.starter.collaboration.domain.entities

/** Durable scheduling generation only; account, runtime and content need independent admission. */
data class CollaborationSpaceRecoveryWake(val generation: String) {
    companion object {
        const val WORK_KEY = "hris.collaboration-space-recovery"
    }
}
