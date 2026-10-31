package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronLeft
import io.github.composefluent.icons.regular.ChevronRight

/** Period content leads; the paired step controls stay separate from app Back navigation. */
@Composable
fun AppPeriodNavigation(
    previousLabel: String,
    nextLabel: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    previousEnabled: Boolean = true,
    nextEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
            content()
        }
        Row {
            AppIconButton(
                Icons.Regular.ChevronLeft,
                previousLabel,
                onPrevious,
                enabled = previousEnabled,
            )
            AppIconButton(Icons.Regular.ChevronRight, nextLabel, onNext, enabled = nextEnabled)
        }
    }
}
