package dev.fajar.starter.localization

import androidx.compose.runtime.Composable
import dev.fajar.starter.common.result.*

@Composable
fun failureText(failure: Failure): String =
    appString(
        when (failure.code) {
            "client.authentication_required" -> AppString.SyncSignInRequired
            "expense_not_editable",
            "expense_submission_not_current" -> AppString.ExpenseActionUnavailable
            "expense_lines_required" -> AppString.ExpenseItemsRequired
            "expense_submission_limit" -> AppString.ExpenseSubmissionLimit
            "expense_receipt_not_ready" -> AppString.ExpenseReceiptInvalid
            "expense_receipt_not_found",
            "expense_receipt_unavailable" -> AppString.ExpenseReceiptUnavailable
            "client.expense_export_timeout",
            "client.expense_selection_timeout" -> AppString.ExpenseExportTimeout
            "expense_transaction_expired",
            "expense_transaction_date_invalid",
            "expense_line_limit_exceeded",
            "expense_category_limit_exceeded",
            "expense_cost_center_required",
            "expense_receipt_required" -> AppString.ExpensePolicyChanged
            "invalid_expense_draft" -> AppString.ExpenseInvalidDraft
            "invalid_expense_reason" -> AppString.ExpenseReasonRequired
            "invalid_expense_line",
            "duplicate_expense_line" -> AppString.ExpenseInvalidItem
            "client.expense_preparation_expired",
            "client.expense_reference_mismatch" -> AppString.ExpensePreparationExpired
            "client.expense_command_pending",
            "client.expense_unconfirmed",
            "client.expense_updates_pending" -> AppString.ExpensePendingRequest
            "expense_claim_amount_limit",
            "expense_line_amount_limit" -> AppString.ExpenseLimitExceeded
            "client.expense_cache_expired",
            "client.expense_review_required" -> AppString.ExpenseReviewRequired
            "expense_claim_not_found",
            "expense_submission_not_found" -> AppString.ExpenseUnavailable
            "client.expense_changed" -> AppString.ExpenseChanged
            "client.expense_working_copy_changed" -> AppString.ExpenseWorkingCopyChanged
            "client.expense_working_copy_invalid" -> AppString.ExpenseWorkingCopyInvalid
            "client.expense_scope_changed" -> AppString.CalendarScopeChanged
            "client.expense_timeout" -> AppString.ExpenseTimeout
            "client.payslip_cache_expired" -> AppString.PayslipExportReviewRequired
            "invalid_payslip_revision",
            "payroll_payslip_not_found" -> AppString.PayslipUnavailable
            "client.payslip_revision_invalid" -> AppString.PayslipUnavailable
            "client.payslip_revision_not_saved" -> AppString.PayslipRevisionNotSaved
            "client.payslip_changed" -> AppString.PayslipChanged
            "client.payslip_scope_changed" -> AppString.CalendarScopeChanged
            "client.payslip_export_timeout",
            "client.payslip_selection_timeout" -> AppString.PayslipExportTimeout
            "payroll_pdf_text_unsupported",
            "payroll_pdf_generation_timeout",
            "payroll_pdf_generation_limit",
            "payroll_pdf_capacity" -> AppString.PayslipPdfUnavailable
            "client.overtime_review_expired" -> AppString.OvertimeReviewExpired
            "client.overtime_time_required" -> AppString.OvertimeInputRequired
            "client.overtime_time_gap" -> AppString.OvertimeTimeGap
            "client.overtime_time_ambiguous" -> AppString.OvertimeTimeAmbiguous
            "client.overtime_command_pending" -> AppString.OvertimeCommandPending
            "client.overtime_unconfirmed" -> AppString.OvertimeCommandUnconfirmed
            "client.overtime_updates_pending" -> AppString.OvertimeUpdatesPending
            "client.overtime_command_changed" -> AppString.OvertimeChanged
            "client.overtime_retry_limit" -> AppString.OvertimeRetryLimit
            "overtime_planning_changed",
            "invalid_overtime_planning_revision" -> AppString.OvertimePlanChanged
            "invalid_overtime_interval",
            "invalid_overtime_actual" -> AppString.OvertimeIntervalInvalid
            "invalid_overtime_request",
            "invalid_overtime_withdrawal" -> AppString.OvertimeReasonInvalid
            "overtime_overlaps_shift" -> AppString.OvertimeOverlapShift
            "overtime_overlap" -> AppString.OvertimeOverlapping
            "overtime_month_capacity" -> AppString.OvertimeCapacity
            "overtime_date_out_of_range",
            "overtime_work_date_mismatch" -> AppString.OvertimeWorkDateInvalid
            "overtime_not_planned",
            "overtime_not_pending" -> AppString.OvertimeActionUnavailable
            "overtime_not_ended" -> AppString.OvertimeNotEnded
            "overtime_outside_requested_window" -> AppString.OvertimeOutsidePlanned
            "overtime_schedule_missing" -> AppString.OvertimeScheduleMissing
            "work_period_locked" -> AppString.OvertimePeriodLocked
            "client.overtime_cache_expired",
            "client.overtime_request_review_required" -> AppString.OvertimeRequestReviewRequired
            "overtime_request_not_found" -> AppString.OvertimeRequestUnavailable
            "client.overtime_changed" -> AppString.OvertimeChanged
            "client.overtime_scope_changed" -> AppString.CalendarScopeChanged
            "client.document_export_timeout" -> AppString.DocumentExportTimeout
            "client.document_checksum_mismatch" -> AppString.DocumentChecksumMismatch

            "client.file_write_failed",
            "client.file_output_failed" -> AppString.FileSaveFailed
            "client.file_exists" -> AppString.FileAlreadyExists
            "client.document_metadata_invalid",
            "invalid_document_metadata" -> AppString.DocumentMetadataInvalid
            "client.document_metadata_change_invalid",
            "invalid_document_metadata_change" -> AppString.DocumentMetadataChangeInvalid
            "client.document_metadata_unsupported" -> AppString.DocumentMetadataUnsupported
            "client.document_metadata_changed" -> AppString.DocumentMetadataStale
            "client.document_working_copy_changed" -> AppString.DocumentMetadataDraftChanged
            "client.document_updates_pending" -> AppString.DocumentMetadataUpdatesPending
            "client.document_command_occupied",
            "client.document_command_changed" -> AppString.DocumentMetadataOccupied
            "client.document_command_unconfirmed" -> AppString.DocumentMetadataUnconfirmed
            "document_metadata_unchanged" -> AppString.DocumentMetadataUnchanged
            "document_metadata_limit" -> AppString.DocumentMetadataLimit
            "client.document_title_invalid" -> AppString.DocumentTitleInvalid
            "client.document_reason_invalid" -> AppString.DocumentReasonInvalid
            "client.document_employment_required" -> AppString.DocumentEmploymentRequired
            "client.document_preparation_expired" -> AppString.DocumentFormExpired
            "client.document_import_incomplete" -> AppString.DocumentImportIncomplete
            "client.document_upload_active",
            "document_upload_active" -> AppString.DocumentUploadActive
            "client.document_upload_waiting",
            "document_chunk_in_progress",
            "document_upload_busy" -> AppString.DocumentUploadWaiting
            "client.document_retry_limit" -> AppString.DocumentRetryLimit
            "client.document_upload_missing",
            "document_revision_not_found" -> AppString.DocumentRemoteMissing
            "client.document_validation_unavailable",
            "document_validation_attempts_exhausted" -> AppString.DocumentValidationUnavailable
            "document_upload_closed" -> AppString.DocumentExpired
            "document_scanner_not_configured",
            "document_scanner_busy",
            "document_inspection_unavailable",
            "document_inspection_failure",
            "document_scanner_protocol_failure" -> AppString.DocumentScanUnavailable
            "client.document_file_name",
            "client.document_file_type",
            "client.document_file_invalid" -> AppString.DocumentInvalidFile
            "client.document_file_size" -> AppString.DocumentFileSize
            "client.document_file_size_unknown" -> AppString.DocumentFileSizeUnknown
            "client.document_storage_full" -> AppString.DocumentStorageFull
            "client.document_file_changed",
            "client.document_import_conflict",
            "client.file_changed" -> AppString.DocumentFileChanged
            "client.file_permission" -> AppString.DocumentFilePermission
            "client.file_read_failed",
            "client.file_input_failed",
            "client.file_unavailable",
            "client.file_released" -> AppString.DocumentFileUnavailable
            "client.document_catalog_changed" -> AppString.TryAgain
            "client.document_evidence_expired" -> AppString.DocumentEvidenceExpired
            "client.document_scope_changed" -> AppString.CalendarScopeChanged
            "client.overtime_working_copy_changed" -> AppString.OvertimeWorkingCopyChanged
            "client.overtime_working_copy_invalid" -> AppString.OvertimeWorkingCopyInvalid
            "client.overtime_working_copy_unavailable" -> AppString.OvertimeWorkingCopyUnavailable
            "client.leave_working_copy_changed" -> AppString.LeaveWorkingCopyChanged
            "client.leave_working_copy_invalid" -> AppString.LeaveWorkingCopyInvalid
            "client.leave_working_copy_unavailable" -> AppString.LeaveWorkingCopyUnavailable
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
            "client.leave_cache_expired",
            "client.leave_review_required",
            "client.leave_command_changed" -> AppString.LeaveReviewRequired
            "client.leave_retry_limit" -> AppString.LeaveRetryLimit
            "client.leave_action_unavailable",
            "leave_not_pending",
            "leave_not_approved" -> AppString.LeaveActionUnavailable
            "leave_year_closed" -> AppString.LeaveYearClosed
            "payroll_period_frozen" -> AppString.LeavePayrollFrozen
            "leave_request_not_found" -> AppString.LeaveRequestUnavailable
            "leave_account_not_found" -> AppString.LeaveBalanceUnavailable
            "client.leave_scope_changed" -> AppString.CalendarScopeChanged
            "client.leave_changed" -> AppString.LeaveChanged
            "client.location_permission" -> AppString.LocationPermission
            "client.location_permanently_denied" -> AppString.LocationPermanent
            "client.location_disabled" -> AppString.LocationDisabled
            "client.location_unavailable",
            "client.location_timeout",
            "client.location_stale" -> AppString.LocationUnavailable
            "client.attendance_cache_expired",
            "client.attendance_review_required" -> AppString.AttendanceReviewRequired
            "client.attendance_refresh_required",
            "client.attendance_changed" -> AppString.AttendanceRefreshRequired
            "client.attendance_already_recorded" -> AppString.AttendanceAlreadyRecorded
            "client.attendance_retry_limit" -> AppString.AttendanceRetryLimit
            "attendance_day_corrected" -> AppString.AttendanceCorrected

            "client.calendar_cache_expired" -> AppString.CalendarRefreshRequired
            "client.calendar_scope_changed" -> AppString.CalendarScopeChanged
            "client.calendar_changed" -> AppString.CalendarChanged
            "client.company_policy_unavailable",
            "client.company_policy_expired" -> AppString.CompanyPolicyUnavailable
            "company_module_disabled" -> AppString.CompanyModuleDisabled
            "client.workspace_expired" -> AppString.WorkspaceAccessExpired
            "client.inbox_item_unavailable",
            "inbox_item_not_found" -> AppString.InboxMessageUnavailable
            "client.inbox_updates_pending" -> AppString.InboxAwaitingSync
            "client.inbox_action_unconfirmed" -> AppString.InboxActionUnconfirmed
            "client.inbox_action_pending" -> AppString.InboxActionPending
            "client.inbox_retry_limit" -> AppString.InboxRetryLimit
            "client.command_queue_full" -> AppString.InboxQueueFull
            "client.inbox_cache_expired",
            "client.inbox_review_required",
            "client.inbox_command_changed",
            "client.inbox_action_unavailable" -> AppString.InboxReviewRequired
            "invalid_credentials" -> AppString.InvalidCredentials
            "firebase_sign_in_failed" -> AppString.ProviderSignInFailed
            "sso_sign_in_failed" -> AppString.ProviderAccountRejected
            "firebase_sign_in_unavailable",
            "identity.firebase_unavailable" -> AppString.ProviderSignInUnavailable
            "identity.session_changed" -> AppString.ErrorUnauthorized
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
