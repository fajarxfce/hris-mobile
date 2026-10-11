package dev.fajar.starter.people.presentation.directory

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.people.domain.entities.DirectoryDepartments

data class DepartmentFilterState(
    val query: String = "",
    val result: DirectoryDepartments? = null,
    val loading: Boolean = true,
    val failure: Failure? = null,
    val needsVerification: Boolean = false,
)
