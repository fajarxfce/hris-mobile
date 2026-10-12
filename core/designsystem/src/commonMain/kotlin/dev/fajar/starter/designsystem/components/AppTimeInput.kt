package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Controlled 24-hour input. The owning form validates digits, ranges and confirmation. */
@Composable
fun AppTimeInput(
    hours: String,
    minutes: String,
    hourLabel: String,
    minuteLabel: String,
    onHoursChange: (String) -> Unit,
    onMinutesChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppTextField(
            hourLabel,
            hours,
            onHoursChange,
            Modifier.weight(1f),
            placeholder = "00",
            enabled = enabled,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        )
        AppTextField(
            minuteLabel,
            minutes,
            onMinutesChange,
            Modifier.weight(1f),
            placeholder = "00",
            enabled = enabled,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        )
    }
}
