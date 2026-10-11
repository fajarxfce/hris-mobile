package dev.fajar.starter.people.presentation.directory.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.people.presentation.directory.EmployeeRowState
import io.github.composefluent.FluentTheme

@Composable
fun EmployeeRow(state: EmployeeRowState) {
    Column(
        Modifier.fillMaxWidth().background(AppColors.surface).semantics(mergeDescendants = true) {}
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).background(AppColors.tint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    state.initials,
                    color = AppColors.accent,
                    style = FluentTheme.typography.bodyStrong,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                AppText(
                    state.name,
                    style = FluentTheme.typography.bodyStrong,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.position != null)
                    AppText(state.position, maxLines = 2, overflow = TextOverflow.Ellipsis)
                AppText(
                    state.metadata,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .padding(start = 74.dp, end = 14.dp)
                .height(1.dp)
                .background(AppColors.border)
        )
    }
}
