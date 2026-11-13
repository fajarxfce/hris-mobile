package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

/**
 * Shared input geometry. Callers own focus, submission semantics and optional surrounding content.
 */
@Composable
internal fun ChatComposerInput(
    text: String,
    onTextChanged: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
    readOnly: Boolean = false,
    inputStateDescription: String? = null,
    action: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChanged,
            modifier =
                inputModifier.weight(1f).heightIn(min = 48.dp).semantics {
                    contentDescription = label
                    inputStateDescription?.let { this.stateDescription = it }
                },
            enabled = enabled,
            readOnly = readOnly,
            singleLine = false,
            maxLines = 6,
            textStyle = FluentTheme.typography.body.copy(color = AppColors.text),
            cursorBrush = SolidColor(AppColors.accent),
            decorationBox = { input ->
                Box(
                    Modifier.background(AppColors.surface, RoundedCornerShape(24.dp))
                        .border(1.dp, AppColors.border, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) AppText(label, color = AppColors.muted)
                    input()
                }
            },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
        )
        action()
    }
}
