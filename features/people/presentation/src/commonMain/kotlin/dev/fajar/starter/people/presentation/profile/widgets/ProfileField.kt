package dev.fajar.starter.people.presentation.profile.widgets

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.AppDetailField

@Composable
fun ProfileField(label: String, value: String?) {
    AppDetailField(label, value ?: "—")
}
