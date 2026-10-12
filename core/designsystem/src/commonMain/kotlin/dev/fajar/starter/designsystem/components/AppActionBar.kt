package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors

/** Fixed page actions. The page owns the bottom system inset. */
@Composable
fun AppActionBar(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(AppColors.canvas)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
