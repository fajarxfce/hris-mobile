package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.composefluent.LocalContentColor
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.ProgressRing

@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    primary: Boolean = true,
) {
    if (primary) {
        AccentButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            disabled = !enabled || loading,
        ) {
            if (loading) {
                ProgressRing(
                    modifier = Modifier.heightIn(max = 18.dp),
                    color = LocalContentColor.current,
                )
            }
            AppText(label, color = LocalContentColor.current)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            disabled = !enabled || loading,
        ) {
            AppText(label, color = LocalContentColor.current)
        }
    }
}
