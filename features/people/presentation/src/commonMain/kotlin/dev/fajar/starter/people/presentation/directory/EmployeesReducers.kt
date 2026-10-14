package dev.fajar.starter.people.presentation.directory

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.replica.entities.*
import dev.fajar.starter.people.domain.replica.policies.directoryFailureClosesRead
import dev.fajar.starter.people.presentation.directory.mappers.toDirectoryContent

fun EmployeesState.directorySelection() =
    DirectorySelection(query, after, department?.id, filter?.query.orEmpty())

fun EmployeesState.withDirectory(result: AppResult<DirectoryView?>): EmployeesState =
    when (result) {
        is AppResult.Failed -> {
            val restricted = directoryFailureClosesRead(result.failure) || readRestricted
            copy(
                page = page?.takeUnless { restricted },
                departments = departments?.takeUnless { restricted },
                loading = false,
                cacheFailure = cacheFailure?.takeIf(::directoryFailureClosesRead) ?: result.failure,
                filter =
                    filter?.copy(
                        loading = false,
                        result = null,
                        failure = result.failure,
                        needsVerification =
                            result.failure.code in
                                setOf(
                                    "mfa_required",
                                    "mfa_setup_required",
                                    "mfa_verification_required",
                                ),
                    ),
            )
        }
        is AppResult.Success -> {
            val value = result.value
            if (
                (value == null && readRestricted) ||
                    (value != null && value.selection != directorySelection())
            )
                this
            else
                copy(
                    page = value?.page?.toDirectoryContent(),
                    departments = value?.departments,
                    loading = value == null && syncing,
                    cacheFailure = null,
                    syncFailure = syncFailure?.takeUnless(::directoryFailureClosesRead),
                    filter =
                        filter?.copy(
                            result = value?.departments,
                            loading = value == null && syncing,
                            failure = null,
                            needsVerification = false,
                        ),
                )
        }
    }

fun EmployeesState.withDirectorySync(result: AppResult<DirectorySyncOutcome>): EmployeesState =
    when (result) {
        is AppResult.Failed -> {
            val restricted = directoryFailureClosesRead(result.failure) || readRestricted
            copy(
                syncing = false,
                loading = false,
                page = page?.takeUnless { restricted },
                departments = departments?.takeUnless { restricted },
                filter = filter?.takeUnless { restricted },
                syncFailure = syncFailure?.takeIf(::directoryFailureClosesRead) ?: result.failure,
            )
        }
        is AppResult.Success ->
            copy(
                syncing = false,
                loading = false,
                syncPending = !result.value.caughtUp,
                syncFailure = if (result.value.caughtUp) null else syncFailure,
                cacheFailure = if (result.value.caughtUp) null else cacheFailure,
            )
    }
