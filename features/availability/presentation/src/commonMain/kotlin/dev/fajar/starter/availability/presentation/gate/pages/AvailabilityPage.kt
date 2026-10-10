package dev.fajar.starter.availability.presentation.gate.pages

import androidx.compose.runtime.Composable
import dev.fajar.starter.availability.domain.entities.AppAvailability
import dev.fajar.starter.availability.presentation.gate.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun AvailabilityPage(state: AvailabilityState, onEvent: (AvailabilityEvent) -> Unit) {
    AppPage {
        AppBrand()
        AppText(
            appString(
                if (state.availability is AppAvailability.Maintenance) AppString.MaintenanceTitle
                else AppString.UpdateRequiredTitle
            ),
            style = FluentTheme.typography.title,
        )
        AppText(
            appString(
                if (state.availability is AppAvailability.Maintenance)
                    AppString.MaintenanceDescription
                else AppString.UpdateRequiredDescription
            )
        )
        if (state.availability is AppAvailability.UpdateRequired) {
            if (state.updateUrl != null)
                AppButton(
                    appString(AppString.UpdateApp),
                    { onEvent(AvailabilityEvent.UpdateRequested) },
                )
            else AppText(appString(AppString.UpdateContactAdministrator))
        }
        if (state.failure != null) AppFeedback(appString(AppString.PolicyRefreshFailed))
        AppButton(
            appString(AppString.Retry),
            { onEvent(AvailabilityEvent.RefreshRequested) },
            enabled = !state.refreshing,
            primary = false,
        )
    }
}
