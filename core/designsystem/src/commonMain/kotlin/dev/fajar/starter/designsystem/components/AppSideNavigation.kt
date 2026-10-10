package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

@Composable
fun <T> AppSideNavigation(
    items: List<AppNavigationItem<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(if (expanded) 224.dp else 96.dp)
            .fillMaxHeight()
            .background(AppColors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .selectableGroup()
            .testTag("app-side-navigation")
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            key(item.value) {
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .selectable(
                            selected = item.value == selected,
                            role = Role.Tab,
                            onClick = { onSelected(item.value) },
                        )
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.width(3.dp)
                            .height(24.dp)
                            .background(
                                if (item.value == selected) AppColors.accent else Color.Transparent,
                                RoundedCornerShape(2.dp),
                            )
                    )
                    if (expanded) {
                        AppIcon(
                            if (item.value == selected) item.selectedIcon else item.icon,
                            tint = if (item.value == selected) AppColors.accent else AppColors.muted,
                        )
                        AppText(
                            item.label,
                            Modifier.weight(1f),
                            color = if (item.value == selected) AppColors.accent else AppColors.text,
                        )
                    } else {
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AppIcon(
                                if (item.value == selected) item.selectedIcon else item.icon,
                                tint =
                                    if (item.value == selected) AppColors.accent
                                    else AppColors.muted,
                            )
                            AppText(
                                item.label,
                                style = FluentTheme.typography.caption,
                                color =
                                    if (item.value == selected) AppColors.accent else AppColors.text,
                            )
                        }
                    }
                }
            }
        }
    }
}
