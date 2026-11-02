package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeRepository
import dev.fajar.starter.common.result.*

/** Seeded admission proves native ownership, not FCM receipt or background content authority. */
internal suspend fun proveHrisRuntimeBorrowing(
    repository: CollaborationRuntimeRepository,
    initial: AndroidCollaborationRuntimeLease,
): AndroidCollaborationRuntimeLease {
    val owner = initial.owner
    val device = initial.deviceId
    val parent =
        CollaborationNativeParent(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            owner.nativeSessionId,
        )
    var foreground: AndroidCollaborationRuntimeLease? = initial
    var background: CollaborationBackgroundRuntimeLease? = null
    try {
        val fingerprint = initial.proofKeyFingerprint()
        requireBorrowedSuccess(
            repository.updateRetention(CollaborationRuntimeRetention(parent, owner))
        )
        background =
            requireNotNull(
                requireBorrowedSuccess(repository.acquireBackground(owner, device, 30_000))
            )
        requireBorrowedSuccess(background.publish())
        requireBorrowedSuccess(
            repository.updateRetention(CollaborationRuntimeRetention(parent, null))
        )
        check(background.interruptions.value == null)
        check(initial.proofKeyFingerprint() == fingerprint)
        val retained = background
        drainHrisProofRuntime { retained.close() }
        background = null
        initial.closeProofRuntime()
        foreground = null
        check(!requireBorrowedSuccess(repository.isOpen(owner, device)))

        // A background-only acquisition restores the same protected store without publishing
        // it before its holder accepts it, and the final reference drains the native client.
        background =
            requireNotNull(
                requireBorrowedSuccess(repository.acquireBackground(owner, device, 30_000))
            )
        val opening = repository.isOpen(owner, device)
        check(
            opening is AppResult.Failed &&
                opening.failure.code == "client.collaboration_runtime_opening"
        )
        requireBorrowedSuccess(background.publish())
        val restoredBackground = background
        drainHrisProofRuntime { restoredBackground.close() }
        background = null
        check(!requireBorrowedSuccess(repository.isOpen(owner, device)))

        requireBorrowedSuccess(
            repository.updateRetention(CollaborationRuntimeRetention(parent, owner))
        )
        val restored =
            requireNotNull(requireBorrowedSuccess(repository.restore(owner, device)))
                as AndroidCollaborationRuntimeLease
        foreground = restored
        check(restored.proofKeyFingerprint() == fingerprint)
        requireBorrowedSuccess(restored.publish())
        check(requireBorrowedSuccess(repository.isOpen(owner, device)))
        background =
            requireNotNull(
                requireBorrowedSuccess(repository.acquireBackground(owner, device, 30_000))
            )
        requireBorrowedSuccess(background.publish())
        val shared = background
        drainHrisProofRuntime { shared.close() }
        background = null
        check(requireBorrowedSuccess(repository.isOpen(owner, device)))
        check(restored.proofKeyFingerprint() == fingerprint)
        return restored.also { foreground = null }
    } catch (failure: Exception) {
        closeHrisProofResources(
            failure,
            listOf(
                { background?.let { borrowed -> drainHrisProofRuntime { borrowed.close() } } },
                { foreground?.closeProofRuntime() },
            ),
        )
        throw failure
    }
}

private fun <T> requireBorrowedSuccess(result: AppResult<T>): T =
    when (result) {
        is AppResult.Success -> result.value
        is AppResult.Failed -> throw HrisProofFailure(result.failure)
    }
