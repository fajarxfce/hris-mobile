package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.fajar.starter.designsystem.theme.AppColors

@Composable
fun AppFeedback(message: String, modifier: Modifier = Modifier) {
    AppCard(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        AppText(message, color = AppColors.error)
    }
}
