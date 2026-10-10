package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Window-size adaptation is a rendering concern; moving the body retains its composition. */
@Composable
fun <T> AppNavigationScaffold(
    items: List<AppNavigationItem<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentContent by rememberUpdatedState(content)
    val body = remember { movableContentOf { currentContent() } }
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth < 600.dp) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) { body() }
                AppBottomNavigation(items, selected, onSelected)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                AppSideNavigation(
                    items,
                    selected,
                    onSelected,
                    expanded = this@BoxWithConstraints.maxWidth >= 840.dp,
                )
                Box(Modifier.weight(1f).fillMaxHeight()) { body() }
            }
        }
    }
}
