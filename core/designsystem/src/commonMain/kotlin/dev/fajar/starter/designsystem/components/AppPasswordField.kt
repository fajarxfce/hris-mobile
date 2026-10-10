package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.filled.Eye
import io.github.composefluent.icons.regular.Eye

@Composable
fun AppPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onVisibilityChanged: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = appString(AppString.Password),
    enabled: Boolean = true,
    error: String? = null,
    keyboardActions: KeyboardActions = KeyboardActions(),
) {
    AppTextField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = appString(AppString.PasswordPlaceholder),
        enabled = enabled,
        error = error,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = keyboardActions,
        trailingContent = {
            AppIconButton(
                icon = if (visible) Icons.Filled.Eye else Icons.Regular.Eye,
                description =
                    if (visible) appString(AppString.HidePassword)
                    else appString(AppString.ShowPassword),
                onClick = onVisibilityChanged,
                enabled = enabled,
            )
        },
    )
}
