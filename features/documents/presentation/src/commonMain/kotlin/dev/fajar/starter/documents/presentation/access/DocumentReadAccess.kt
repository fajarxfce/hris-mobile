package dev.fajar.starter.documents.presentation.access

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workspace.domain.policies.workspaceAccessLossCodes

internal fun documentReadRestricted(failure: Failure): Boolean =
    failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied) ||
        failure.code in workspaceAccessLossCodes ||
        failure.code in
            setOf(
                "company_module_disabled",
                "company_maintenance",
                "client_update_required",
                "client_version_required",
                "client.company_policy_expired",
                "client.company_policy_unavailable",
            )
