package dev.fajar.starter.people.presentation.directory

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.people.domain.entities.CompanyDirectory

data class EmployeesState(
    val query: String = "",
    val after: String? = null,
    val page: CompanyDirectory? = null,
    val loading: Boolean = false,
    val failure: Failure? = null,
    val needsVerification: Boolean = false,
)
