package dev.fajar.starter.people.presentation.directory

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.people.domain.entities.DirectoryDepartment
import dev.fajar.starter.people.domain.entities.DirectoryDepartments
import dev.fajar.starter.people.domain.replica.policies.directoryFailureClosesRead

data class EmployeesState(
    val query: String = "",
    val after: String? = null,
    val page: EmployeeDirectoryContent? = null,
    val department: DirectoryDepartment? = null,
    val filter: DepartmentFilterState? = null,
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val syncPending: Boolean = false,
    val departments: DirectoryDepartments? = null,
    val cacheFailure: Failure? = null,
    val syncFailure: Failure? = null,
) {
    val readRestricted: Boolean
        get() =
            cacheFailure?.let(::directoryFailureClosesRead) == true ||
                syncFailure?.let(::directoryFailureClosesRead) == true

    val failure: Failure?
        get() = cacheFailure?.takeIf(::directoryFailureClosesRead) ?: syncFailure ?: cacheFailure

    val needsVerification: Boolean
        get() =
            failure?.code in
                setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
}
