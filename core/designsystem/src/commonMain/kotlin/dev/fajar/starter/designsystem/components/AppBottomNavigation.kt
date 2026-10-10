package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

@Composable
fun <T> AppBottomNavigation(
    items: List<AppNavigationItem<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(AppColors.surface)
            .navigationBarsPadding()
            .testTag("app-bottom-navigation"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.widthIn(max = 600.dp)
                .fillMaxWidth()
                .selectableGroup()
                .padding(horizontal = 8.dp)
        ) {
            items.forEach { item ->
                key(item.value) {
                    val interactionSource = remember(item.value) { MutableInteractionSource() }
                    Column(
                        Modifier.weight(1f)
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = item.value == selected,
                                role = Role.Tab,
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onSelected(item.value) },
                            )
                            .padding(top = 4.dp, bottom = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(40.dp)
                                .clip(CircleShape)
                                .indication(interactionSource, LocalIndication.current),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppIcon(
                                if (item.value == selected) item.selectedIcon else item.icon,
                                tint =
                                    if (item.value == selected) AppColors.accent
                                    else AppColors.muted,
                            )
                        }
                        AppText(
                            item.label,
                            style =
                                FluentTheme.typography.caption.copy(
                                    fontWeight =
                                        if (item.value == selected) FontWeight.SemiBold
                                        else FontWeight.Normal
                                ),
                            color =
                                if (item.value == selected) AppColors.accent else AppColors.muted,
                        )
                    }
                }
            }
        }
    }
}
