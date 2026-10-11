package dev.fajar.starter.workspace.presentation.policy

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workspace.domain.entities.CompanyAvailability

data class CompanyPolicyState(
    val company: String,
    val availability: CompanyAvailability,
    val refreshing: Boolean,
    val changingCompany: Boolean,
    val failure: Failure?,
)
