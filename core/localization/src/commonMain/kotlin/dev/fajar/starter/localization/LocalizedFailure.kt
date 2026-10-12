package dev.fajar.starter.localization

import androidx.compose.runtime.Composable
import dev.fajar.starter.common.result.*

@Composable
fun failureText(failure: Failure): String =
    appString(
        when (failure.code) {
            "client.leave_preview_expired" -> AppString.LeavePreviewExpired
            "leave_preview_changed",
            "invalid_leave_preview" -> AppString.LeavePreviewChanged
            "client.leave_submission_pending" -> AppString.LeaveSubmissionPending
            "client.leave_submission_unconfirmed" -> AppString.LeaveSubmissionUnconfirmed
            "leave_range_too_large" -> AppString.LeaveDateRangeInvalid
            "leave_type_unavailable" -> AppString.LeaveNoTypes
            "no_leave_working_days" -> AppString.LeaveNoWorkingDays
            "leave_schedule_missing" -> AppString.LeaveScheduleMissing
            "insufficient_leave_balance",
            "leave_balance_limit" -> AppString.LeaveBalanceInsufficient
            "partial_leave_unavailable" -> AppString.LeavePartialUnavailable
            "leave_duration_too_short",
            "leave_request_limit" -> AppString.LeaveDurationInvalid
            "leave_overlap" -> AppString.LeaveOverlapping
            "leave_employee_ineligible" -> AppString.LeaveEmployeeIneligible
            "leave_attachment_required" -> AppString.LeaveAttachmentRequired
            "leave_attachment_unavailable" -> AppString.LeaveEvidenceUnavailable
            "client.leave_reason_required" -> AppString.LeaveReasonRequired
            "client.leave_reason_too_long" -> AppString.LeaveReasonLimit
            "client.leave_action_pending" -> AppString.LeaveActionPending
            "client.leave_action_unconfirmed" -> AppString.LeaveActionUnconfirmed
            "client.leave_updates_pending" -> AppString.LeaveActionConfirmed
            "client.leave_review_required",
            "client.leave_command_changed" -> AppString.LeaveReviewRequired
            "client.leave_retry_limit" -> AppString.LeaveRetryLimit
            "client.leave_action_unavailable",
            "leave_not_pending",
            "leave_not_approved" -> AppString.LeaveActionUnavailable
            "leave_year_closed" -> AppString.LeaveYearClosed
            "payroll_period_frozen" -> AppString.LeavePayrollFrozen
            "leave_request_not_found" -> AppString.LeaveRequestUnavailable
            "client.leave_cache_expired" -> AppString.LeaveCacheExpired
            "client.leave_scope_changed" -> AppString.CalendarScopeChanged
            "client.leave_changed" -> AppString.LeaveChanged
            "client.location_permission" -> AppString.LocationPermission
            "client.location_permanently_denied" -> AppString.LocationPermanent
            "client.location_disabled" -> AppString.LocationDisabled
            "client.location_unavailable",
            "client.location_timeout",
            "client.location_stale" -> AppString.LocationUnavailable
            "client.attendance_cache_expired" -> AppString.AttendanceCacheExpired
            "client.attendance_refresh_required",
            "client.attendance_changed" -> AppString.AttendanceRefreshRequired
            "client.attendance_already_recorded" -> AppString.AttendanceAlreadyRecorded
            "client.attendance_retry_limit" -> AppString.AttendanceRetryLimit
            "attendance_day_corrected" -> AppString.AttendanceCorrected

            "client.calendar_cache_expired" -> AppString.CalendarCacheExpired
            "client.calendar_scope_changed" -> AppString.CalendarScopeChanged
            "client.calendar_changed" -> AppString.CalendarChanged
            "client.company_policy_unavailable",
            "client.company_policy_expired" -> AppString.CompanyPolicyUnavailable
            "company_module_disabled" -> AppString.CompanyModuleDisabled
            "client.workspace_expired" -> AppString.WorkspaceAccessExpired
            "client.inbox_cache_expired" -> AppString.InboxCacheExpired
            "client.inbox_item_unavailable",
            "inbox_item_not_found" -> AppString.InboxMessageUnavailable
            "client.inbox_updates_pending" -> AppString.InboxAwaitingSync
            "client.inbox_action_unconfirmed" -> AppString.InboxActionUnconfirmed
            "client.inbox_action_pending" -> AppString.InboxActionPending
            "client.inbox_retry_limit" -> AppString.InboxRetryLimit
            "client.command_queue_full" -> AppString.InboxQueueFull
            "client.inbox_review_required",
            "client.inbox_command_changed",
            "client.inbox_action_unavailable" -> AppString.InboxReviewRequired
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
