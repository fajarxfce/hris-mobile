package dev.fajar.starter.collaboration.domain.entities

/** A local scheduling checkpoint, never account ownership or permission to read messages. */
data class CollaborationRecoveryWake(val generation: String) {
    companion object {
        const val WORK_KEY = "hris.collaboration-recovery"
    }
}
