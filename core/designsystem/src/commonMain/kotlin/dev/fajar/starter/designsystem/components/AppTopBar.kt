package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .testTag("app-top-bar")
            .fillMaxWidth()
            .background(AppColors.canvas)
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        navigation?.invoke()
        Column(
            Modifier.weight(1f).padding(start = if (navigation == null) 8.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AppText(
                title,
                modifier = Modifier.semantics { heading() },
                style =
                    FluentTheme.typography.bodyStrong.copy(fontSize = 18.sp, lineHeight = 24.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null)
                AppText(
                    subtitle,
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
        }
        actions()
    }
}
