package dev.fajar.starter.workspace.domain.policies

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.time.Instant

fun companyPolicyDeadline(snapshot: CompanyPolicySnapshot): Instant =
    snapshot.requestedAt + (snapshot.policy.validUntil - snapshot.policy.serverTime)

fun companyPolicyIsFresh(snapshot: CompanyPolicySnapshot?, now: Instant): Boolean =
    snapshot != null && snapshot.requestedAt <= now && now < companyPolicyDeadline(snapshot)

fun companyAvailability(
    snapshot: CompanyPolicySnapshot?,
    build: AppBuild,
    now: Instant,
): CompanyAvailability {
    if (snapshot == null || now < snapshot.requestedAt) return CompanyAvailability.Unknown
    val policy = snapshot.policy
    val minimum =
        when (build.platform) {
            AppPlatform.Android -> policy.minimumBuilds.android
            AppPlatform.Ios -> policy.minimumBuilds.ios
            AppPlatform.Web -> policy.minimumBuilds.web
            AppPlatform.Desktop -> {
                if (maxOf(policy.minimumBuilds.android, policy.minimumBuilds.ios) > 0)
                    return CompanyAvailability.ClientMetadataRequired
                0
            }
        }
    if (build.number < minimum) return CompanyAvailability.UpdateRequired(minimum)
    policy.maintenance?.let { window ->
        val scheduledStart = snapshot.requestedAt + (window.startsAt - policy.serverTime)
        // Known maintenance stays blocked until another server response confirms it has ended.
        if (
            policy.maintenanceActive ||
                (window.startsAt > policy.serverTime && now >= scheduledStart)
        )
            return CompanyAvailability.Maintenance(window.endsAt)
    }
    return CompanyAvailability.Available
}

fun companyModuleAvailable(workspace: Workspace, module: CompanyModule): Boolean =
    workspace.policy?.policy?.enabledModules?.contains(module) == true

fun requireCompanyModule(workspace: Workspace, module: CompanyModule): AppResult<Unit> =
    when {
        workspace.policy == null ->
            companyPolicyFailure("client.company_policy_unavailable", FailureKind.Unavailable)
        !companyModuleAvailable(workspace, module) ->
            companyPolicyFailure("company_module_disabled")
        else -> AppResult.Success(Unit)
    }

/** Sending requires fresh policy; an availability rejection never consumes an operation ID. */
fun admitCompanyOperation(
    snapshot: CompanyPolicySnapshot,
    build: AppBuild,
    module: CompanyModule,
    now: Instant,
): AppResult<Unit> =
    when (companyAvailability(snapshot, build, now)) {
        CompanyAvailability.Unknown ->
            companyPolicyFailure("client.company_policy_unavailable", FailureKind.Unavailable)
        CompanyAvailability.ClientMetadataRequired ->
            companyPolicyFailure("client_version_required")
        is CompanyAvailability.UpdateRequired -> companyPolicyFailure("client_update_required")
        is CompanyAvailability.Maintenance ->
            companyPolicyFailure("company_maintenance", FailureKind.Service)
        CompanyAvailability.Available ->
            when {
                module !in snapshot.policy.enabledModules ->
                    companyPolicyFailure("company_module_disabled")
                !companyPolicyIsFresh(snapshot, now) ->
                    companyPolicyFailure("client.company_policy_expired", FailureKind.Unavailable)
                else -> AppResult.Success(Unit)
            }
    }

fun companyPolicyFailure(code: String, kind: FailureKind = FailureKind.AccessDenied) =
    AppResult.Failed(Failure(kind, "Company availability must be checked.", code = code))
