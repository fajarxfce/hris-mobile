package dev.fajar.starter.people.presentation.profile

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.people.domain.entities.*

data class ProfileState(
    val directory: SelfEmploymentDirectory? = null,
    val selectedId: String? = null,
    val profile: EmploymentProfile? = null,
    val loadingDirectory: Boolean = false,
    val loadingProfile: Boolean = false,
    val failure: Failure? = null,
    val needsVerification: Boolean = false,
)
