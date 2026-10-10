package dev.fajar.starter.availability.presentation.gate.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.availability.presentation.gate.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*

@Composable
fun UpdateBanner(state: AvailabilityState, onEvent: (AvailabilityEvent) -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(12.dp)) {
        AppText(appString(AppString.UpdateRecommendedDescription))
        if (state.updateUrl != null)
            AppButton(
                appString(AppString.UpdateApp),
                { onEvent(AvailabilityEvent.UpdateRequested) },
            )
        AppButton(
            appString(AppString.Later),
            { onEvent(AvailabilityEvent.DismissRequested) },
            primary = false,
        )
        if (state.failure != null) AppFeedback(appString(AppString.PolicyRefreshFailed))
    }
}
