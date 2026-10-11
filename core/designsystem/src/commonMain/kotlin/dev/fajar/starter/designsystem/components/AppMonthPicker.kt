package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronLeft
import io.github.composefluent.icons.regular.ChevronRight

@Immutable
data class AppMonthChoice(
    val number: Int,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

/** Controlled calendar input. The caller owns the selected period and available years/months. */
@Composable
fun AppMonthPicker(
    yearLabel: String,
    months: List<AppMonthChoice>,
    onMonthSelected: (Int) -> Unit,
    previousYearLabel: String,
    nextYearLabel: String,
    onPreviousYear: (() -> Unit)?,
    onNextYear: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(
                Icons.Regular.ChevronLeft,
                previousYearLabel,
                { onPreviousYear?.invoke() },
                enabled = onPreviousYear != null,
            )
            AppHeading(
                yearLabel,
                Modifier.weight(1f),
                style = FluentTheme.typography.bodyStrong.copy(textAlign = TextAlign.Center),
            )
            AppIconButton(
                Icons.Regular.ChevronRight,
                nextYearLabel,
                { onNextYear?.invoke() },
                enabled = onNextYear != null,
            )
        }
        val minimumWidth = 104.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
            val columns = ((maxWidth + 8.dp) / (minimumWidth + 8.dp)).toInt().coerceIn(1, 3)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                months.chunked(columns).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { month ->
                            key(month.number) {
                                Box(
                                    Modifier.weight(1f)
                                        .heightIn(min = 56.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (month.selected) AppColors.tint
                                            else AppColors.surface
                                        )
                                        .alpha(if (month.enabled) 1f else 0.4f)
                                        .selectable(
                                            selected = month.selected,
                                            enabled = month.enabled,
                                            role = Role.RadioButton,
                                            onClick = { onMonthSelected(month.number) },
                                        )
                                        .padding(horizontal = 8.dp, vertical = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AppText(
                                        month.label,
                                        color =
                                            if (month.selected) AppColors.accent
                                            else AppColors.text,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
