package dev.fajar.starter.localization

import androidx.compose.runtime.Composable
import dev.fajar.starter.common.result.*

@Composable
fun failureText(failure: Failure): String =
    appString(
        when (failure.code) {
            "invalid_credentials" -> AppString.InvalidCredentials
            "invalid_mfa_code",
            "invalid_mfa_request" -> AppString.InvalidVerificationCode
            "mfa_enrollment_expired" -> AppString.EnrollmentExpired
            "mfa_rate_limited",
            "sign_in_rate_limited",
            "authentication_rate_limited" -> AppString.TooManyAttempts
            "company_access_denied" -> AppString.ErrorAccessDenied
            "client_update_required",
            "client_version_required" -> AppString.UpdateRequiredDescription
            "company_maintenance" -> AppString.MaintenanceDescription
            else ->
                when (failure.kind) {
                    FailureKind.Cancelled -> AppString.ErrorCancelled
                    FailureKind.Validation -> AppString.ErrorValidation
                    FailureKind.Conflict -> AppString.ErrorConflict
                    FailureKind.Unauthorized -> AppString.ErrorUnauthorized
                    FailureKind.Network -> AppString.ErrorNetwork
                    FailureKind.Timeout -> AppString.ErrorTimeout
                    FailureKind.Service -> AppString.ErrorService
                    FailureKind.Storage -> AppString.ErrorStorage
                    FailureKind.Permission -> AppString.ErrorPermission
                    FailureKind.AccessDenied -> AppString.ErrorAccessDenied
                    FailureKind.Unavailable -> AppString.ErrorUnavailable
                    FailureKind.Unexpected -> AppString.ErrorUnexpected
                }
        }
    )

@Composable
fun fieldErrorText(issue: ValidationIssue?): String? =
    issue?.let {
        appString(
            when (it) {
                ValidationIssue.Required -> AppString.FieldRequired
                ValidationIssue.Invalid -> AppString.FieldInvalid
                ValidationIssue.AlreadyExists -> AppString.FieldExists
                ValidationIssue.Rejected -> AppString.FieldRejected
            }
        )
    }
