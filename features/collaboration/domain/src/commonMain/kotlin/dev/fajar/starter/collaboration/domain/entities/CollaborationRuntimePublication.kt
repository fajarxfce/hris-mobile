package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.AppResult

/** Processed resource retention, not acquisition permission or proof that cleanup finished. */
data class CollaborationRuntimePublication(
    val retention: CollaborationRuntimeRetention,
    val ownership: AppResult<CollaborationOwner?>,
    val cleanup: AppResult<Unit>,
) {
    /** Preserve confirmed ownership denial ahead of a native cleanup failure. */
    val result: AppResult<CollaborationOwner?>
        get() =
            if (ownership is AppResult.Failed) ownership
            else if (cleanup is AppResult.Failed) cleanup else ownership

    override fun toString() = "CollaborationRuntimePublication([redacted])"
}
