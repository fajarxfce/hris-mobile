package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Overlay whose action hides on consumed downward scroll and returns on upward scroll. */
@Composable
fun AppFloatingActionLayout(
    modifier: Modifier = Modifier,
    stateKey: Any? = null,
    action: @Composable BoxScope.(Boolean) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var showAction by rememberSaveable(stateKey) { mutableStateOf(true) }
    val threshold = with(LocalDensity.current) { 12.dp.toPx() }
    val scroll =
        remember(stateKey, threshold) {
            object : NestedScrollConnection {
                private var travelled = 0f

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                        if (travelled * consumed.y < 0f) travelled = 0f
                        travelled += consumed.y
                        if (abs(travelled) >= threshold) {
                            showAction = travelled > 0f
                            travelled = 0f
                        }
                    }
                    return Offset.Zero
                }
            }
        }
    Box(modifier.nestedScroll(scroll)) {
        content()
        action(showAction)
    }
}
