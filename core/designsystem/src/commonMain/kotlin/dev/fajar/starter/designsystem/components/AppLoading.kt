package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.ProgressRing

@Composable
fun AppLoading(
    label: String = appString(AppString.Loading),
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (compact) {
        Row(
            modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProgressRing(modifier = Modifier.size(20.dp))
            AppText(label, style = FluentTheme.typography.caption)
        }
        return
    }
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProgressRing()
        AppText(label)
    }
}
