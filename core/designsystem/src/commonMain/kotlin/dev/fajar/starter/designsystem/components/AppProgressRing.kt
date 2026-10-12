package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.composefluent.component.ProgressRing

@Composable
fun AppProgressRing(description: String, modifier: Modifier = Modifier) {
    ProgressRing(modifier.size(20.dp).semantics { contentDescription = description })
}
