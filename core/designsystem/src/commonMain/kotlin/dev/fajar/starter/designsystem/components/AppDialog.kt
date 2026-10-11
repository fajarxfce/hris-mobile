package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Dismiss

/** The caller owns visibility; dismissal, focus and window lifetime use the platform dialog. */
@Composable
fun AppDialog(
    title: String,
    closeLabel: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppCard(
                Modifier.widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(16.dp)),
                padding = 0.dp,
            ) {
                AppTopBar(
                    title,
                    actions = { AppIconButton(Icons.Regular.Dismiss, closeLabel, onDismiss) },
                )
                Column(Modifier.weight(1f, fill = false), content = content)
            }
        }
    }
}
