package dev.fajar.starter.workspace.presentation.policy.pages

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workspace.domain.entities.CompanyAvailability
import dev.fajar.starter.workspace.presentation.policy.*

@Composable
fun CompanyPolicyPage(state: CompanyPolicyState, onEvent: (CompanyPolicyEvent) -> Unit) {
    AppPage {
        AppBrand()
        AppText(state.company)
        AppHeading(
            appString(
                when (state.availability) {
                    is CompanyAvailability.Maintenance -> AppString.MaintenanceTitle
                    is CompanyAvailability.UpdateRequired,
                    CompanyAvailability.ClientMetadataRequired -> AppString.UpdateRequiredTitle
                    else -> AppString.CompanyPolicyTitle
                }
            )
        )
        AppText(
            appString(
                when (state.availability) {
                    is CompanyAvailability.Maintenance -> AppString.MaintenanceDescription
                    is CompanyAvailability.UpdateRequired -> AppString.UpdateRequiredDescription
                    CompanyAvailability.ClientMetadataRequired ->
                        AppString.UpdateContactAdministrator
                    else -> AppString.CompanyPolicyUnavailable
                }
            )
        )
        if (state.failure != null) AppFeedback(failureText(state.failure))
        AppButton(
            appString(AppString.Retry),
            { onEvent(CompanyPolicyEvent.RefreshRequested) },
            enabled = !state.refreshing && !state.changingCompany,
            loading = state.refreshing,
        )
        AppButton(
            appString(AppString.ChangeCompany),
            { onEvent(CompanyPolicyEvent.CompanyChangeRequested) },
            primary = false,
            loading = state.changingCompany,
        )
    }
}
