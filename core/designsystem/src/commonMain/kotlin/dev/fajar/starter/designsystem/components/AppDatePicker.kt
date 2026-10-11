package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronLeft
import io.github.composefluent.icons.regular.ChevronRight

/**
 * Controlled month grid. The caller supplies seven weekday labels and leading null cells, owns the
 * calendar, selection and bounds, and provides localized full-date descriptions.
 */
@Composable
fun AppDatePicker(
    monthLabel: String,
    weekdays: List<String>,
    dates: List<AppDateChoice?>,
    onDateSelected: (Int) -> Unit,
    previousMonthLabel: String,
    nextMonthLabel: String,
    onPreviousMonth: (() -> Unit)?,
    onNextMonth: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(
                Icons.Regular.ChevronLeft,
                previousMonthLabel,
                { onPreviousMonth?.invoke() },
                enabled = onPreviousMonth != null,
            )
            AppHeading(
                monthLabel,
                Modifier.weight(1f),
                style = FluentTheme.typography.bodyStrong.copy(textAlign = TextAlign.Center),
            )
            AppIconButton(
                Icons.Regular.ChevronRight,
                nextMonthLabel,
                { onNextMonth?.invoke() },
                enabled = onNextMonth != null,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            weekdays.forEach { label ->
                AppText(
                    label,
                    Modifier.weight(1f),
                    style = FluentTheme.typography.caption,
                    color = AppColors.muted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            dates.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        if (date == null) Spacer(Modifier.weight(1f))
                        else
                            key(date.day) {
                                Box(
                                    Modifier.weight(1f)
                                        .heightIn(min = 48.dp)
                                        .padding(horizontal = 2.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (date.selected) AppColors.tint else AppColors.surface
                                        )
                                        .alpha(if (date.enabled) 1f else 0.4f)
                                        .selectable(
                                            selected = date.selected,
                                            enabled = date.enabled,
                                            role = Role.RadioButton,
                                            onClick = { onDateSelected(date.day) },
                                        )
                                        .semantics { contentDescription = date.description }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AppText(
                                        date.label,
                                        Modifier.clearAndSetSemantics {},
                                        color =
                                            if (date.selected) AppColors.accent else AppColors.text,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
