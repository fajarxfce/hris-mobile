package dev.fajar.starter.inbox.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.*

const val INBOX_AUTOMATIC_ATTEMPTS = 3
const val INBOX_MAXIMUM_ATTEMPTS = 1_000

fun InboxCommand.belongsTo(access: InboxAccess) =
    accountId == access.accountId && audience == access.audience && companyId == access.companyId

fun validateInboxCommandAccess(
    current: AppResult<InboxAccess>,
    expected: InboxAccess,
): AppResult<Unit> =
    when (current) {
        is AppResult.Failed -> current
        is AppResult.Success ->
            if (sameInboxScope(current.value, expected)) AppResult.Success(Unit)
            else inboxCommandFailure("client.inbox_scope_changed", FailureKind.AccessDenied)
    }

fun inboxActionApplied(action: InboxAction, item: InboxItem) =
    when (action) {
        InboxAction.READ -> item.readAt != null
        InboxAction.ACKNOWLEDGE -> item.acknowledgedAt != null
    }

/** Only documented transactional rejections can establish that a first attempt did not commit. */
fun resolveInboxCommandFailure(
    previous: InboxCommandOutcome,
    failure: Failure,
): InboxCommandOutcome =
    if (previous !is InboxCommandOutcome.Unconfirmed && failure.code in definiteInboxRejections)
        InboxCommandOutcome.Rejected(failure)
    else InboxCommandOutcome.Unconfirmed(failure)

fun inboxCommandFailure(code: String, kind: FailureKind = FailureKind.Conflict) =
    AppResult.Failed(Failure(kind, "Refresh the inbox and review this action.", code = code))

private val definiteInboxRejections =
    setOf(
        "stale_version",
        "invalid_version",
        "inbox_acknowledgement_not_required",
        "inbox_item_not_found",
        "company_access_denied",
        "sync_access_denied",
        "session_revoked",
        "forbidden",
        "mfa_required",
        "mfa_setup_required",
        "recent_authentication_required",
        "mfa_verification_required",
    )
