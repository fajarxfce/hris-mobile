package dev.fajar.starter.people.presentation.profile

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.people.domain.profile.entities.SelfProfileView
import dev.fajar.starter.people.domain.profile.policies.selfProfileFailureClosesRead

data class ProfileState(
    val view: SelfProfileView? = null,
    val selectedId: String? = null,
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val syncPending: Boolean = false,
    val choosingEmployment: Boolean = false,
    val readRestricted: Boolean = false,
    val cacheFailure: Failure? = null,
    val syncFailure: Failure? = null,
) {
    val failure: Failure?
        get() = cacheFailure?.takeIf(::selfProfileFailureClosesRead) ?: syncFailure ?: cacheFailure

    val needsVerification: Boolean
        get() =
            failure?.code in
                setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
}
