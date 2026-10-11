package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.component.TextField
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Dismiss
import io.github.composefluent.icons.regular.Search

/** Search has no visible form header; its label remains available to accessibility services. */
@Composable
fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val focus = LocalFocusManager.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label },
        enabled = enabled,
        singleLine = true,
        isClearable = false,
        shape = RoundedCornerShape(12.dp),
        placeholder = { AppText(label, color = AppColors.muted) },
        leadingIcon = { AppIcon(Icons.Regular.Search, tint = AppColors.muted) },
        trailing = {
            if (value.isNotEmpty())
                AppIconButton(
                    Icons.Regular.Dismiss,
                    clearLabel,
                    { onValueChange("") },
                    enabled = enabled,
                )
            else Spacer(Modifier.height(48.dp))
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}
