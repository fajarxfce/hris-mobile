package dev.fajar.starter.people.presentation.directory

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.people.domain.entities.DirectoryDepartment

data class EmployeesState(
    val query: String = "",
    val after: String? = null,
    val page: EmployeeDirectoryContent? = null,
    val department: DirectoryDepartment? = null,
    val filter: DepartmentFilterState? = null,
    val loading: Boolean = false,
    val failure: Failure? = null,
    val needsVerification: Boolean = false,
)
